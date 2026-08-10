package com.genymobile.scrcpy.daemon.net;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single client session can now open multiple instances of the same role
 * (ROLE_VIDEO, ROLE_AUDIO, ROLE_CONTROL) to drive different displays.
 *
 * <p>Socket handshake update:
 * <pre>
 *   +----------+-----------------------+--------------------+
 *   | 1 byte   | 4 bytes (ROLE_NEGO only) | 4 bytes (others)  |
 *   | role     | sessionId             | displayId          |
 *   +----------+-----------------------+--------------------+
 * </pre>
 *
 * <p>For ROLE_VIDEO / ROLE_AUDIO / ROLE_CONTROL the client appends a
 * 4-byte big-endian {@code displayId} immediately after the sessionId.
 * This lets the server demux N ROLE_VIDEO sockets (one per display) inside
 * a single session. ROLE_NEGOTIATION has no displayId because it is a
 * session-wide control plane.
 *
 * <p>The connection stores a map of {@code (role, displayId) → channel} so
 * the session can iterate all subscribers on cleanup and so re-bind of the
 * same key replaces a stale socket (reconnect semantics).
 */
public final class TcpDesktopConnection implements Closeable {

    public static final int ROLE_VIDEO = 0;
    public static final int ROLE_AUDIO = 1;
    public static final int ROLE_CONTROL = 2;
    public static final int ROLE_NEGOTIATION = 3;

    public static final int ROLE_BIT_VIDEO = 1 << ROLE_VIDEO;
    public static final int ROLE_BIT_AUDIO = 1 << ROLE_AUDIO;
    public static final int ROLE_BIT_CONTROL = 1 << ROLE_CONTROL;
    public static final int ROLE_BIT_ALL = ROLE_BIT_VIDEO | ROLE_BIT_AUDIO | ROLE_BIT_CONTROL;

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    /** Composite key for ROLE_* / ROLE_CONTROL channels. */
    public static final class RoleDisplayKey {
        public final int role;
        public final int displayId;

        public RoleDisplayKey(int role, int displayId) {
            this.role = role;
            this.displayId = displayId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RoleDisplayKey)) return false;
            RoleDisplayKey that = (RoleDisplayKey) o;
            return role == that.role && displayId == that.displayId;
        }

        @Override
        public int hashCode() {
            return 31 * role + displayId;
        }

        @Override
        public String toString() {
            String roleName;
            switch (role) {
                case ROLE_VIDEO: roleName = "VIDEO"; break;
                case ROLE_AUDIO: roleName = "AUDIO"; break;
                case ROLE_CONTROL: roleName = "CONTROL"; break;
                default: roleName = "ROLE_" + role; break;
            }
            return roleName + ":displayId=" + displayId;
        }
    }

    /** Shared container for a ROLE_VIDEO / ROLE_AUDIO channel (socket+fd). */
    public static final class FdChannel {
        public final Socket socket;
        public final FileDescriptor fd;

        public FdChannel(Socket socket, FileDescriptor fd) {
            this.socket = socket;
            this.fd = fd;
        }
    }

    // Negotiation channel is session-scoped — one per session, no displayId.
    private final Socket negotiationSocket;
    private final ControlChannel negotiationChannel;

    // Role channels are keyed by (role, displayId). Volatile for safe publish
    // from the accept thread; mutations are synchronized on the map itself.
    private final Map<RoleDisplayKey, FdChannel> fdChannels = new HashMap<>();
    private final Map<RoleDisplayKey, ControlChannel> controlChannels = new HashMap<>();

    private final int sessionId;

    public TcpDesktopConnection(Socket negotiationSocket, int sessionId) throws IOException {
        this.negotiationSocket = negotiationSocket;
        this.sessionId = sessionId;
        this.negotiationChannel = negotiationSocket != null
                ? new ControlChannel(negotiationSocket.getInputStream(), negotiationSocket.getOutputStream())
                : null;
    }

    public ControlChannel getNegotiationChannel() {
        return negotiationChannel;
    }

    public int getSessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------------
    // Bind / accessors
    // ------------------------------------------------------------------

    public ControlChannel bindControlSocket(int displayId, Socket socket) throws IOException {
        RoleDisplayKey key = new RoleDisplayKey(ROLE_CONTROL, displayId);
        synchronized (controlChannels) {
            ControlChannel prev = controlChannels.remove(key);
            if (prev != null) {
                // Close the stale underlying socket remembered in fdChannels.
                synchronized (fdChannels) {
                    FdChannel stale = fdChannels.remove(key);
                    if (stale != null) closeQuietly(stale.socket);
                }
            }
            ControlChannel ch = new ControlChannel(socket.getInputStream(), socket.getOutputStream());
            controlChannels.put(key, ch);
            // Remember the raw socket in fdChannels so shutdown/close iterates it.
            synchronized (fdChannels) {
                fdChannels.put(key, new FdChannel(socket, null));
            }
            Ln.i("TcpDesktopConnection[" + sessionId + "]: control socket bound " + key);
            return ch;
        }
    }

    public FdChannel bindVideoSocket(int displayId, Socket socket) throws IOException {
        RoleDisplayKey key = new RoleDisplayKey(ROLE_VIDEO, displayId);
        FileDescriptor fd = getFileDescriptor(socket);
        FdChannel ch = new FdChannel(socket, fd);
        synchronized (fdChannels) {
            fdChannels.put(key, ch);
            Ln.i("TcpDesktopConnection[" + sessionId + "]: video socket bound " + key + ", fd=" + fd);
        }
        return ch;
    }

    public FdChannel bindAudioSocket(int displayId, Socket socket) throws IOException {
        RoleDisplayKey key = new RoleDisplayKey(ROLE_AUDIO, displayId);
        FileDescriptor fd = getFileDescriptor(socket);
        FdChannel ch = new FdChannel(socket, fd);
        synchronized (fdChannels) {
            fdChannels.put(key, ch);
            Ln.i("TcpDesktopConnection[" + sessionId + "]: audio socket bound " + key + ", fd=" + fd);
        }
        return ch;
    }

    public ControlChannel getControlChannel(int displayId) {
        synchronized (controlChannels) {
            return controlChannels.get(new RoleDisplayKey(ROLE_CONTROL, displayId));
        }
    }

    public FdChannel getVideoChannel(int displayId) {
        synchronized (fdChannels) {
            return fdChannels.get(new RoleDisplayKey(ROLE_VIDEO, displayId));
        }
    }

    public FdChannel getAudioChannel(int displayId) {
        synchronized (fdChannels) {
            return fdChannels.get(new RoleDisplayKey(ROLE_AUDIO, displayId));
        }
    }

    /** Snapshot of currently bound (role,displayId) keys — stable for iteration. */
    public List<RoleDisplayKey> listBoundKeys() {
        List<RoleDisplayKey> keys = new ArrayList<>();
        synchronized (fdChannels) {
            keys.addAll(fdChannels.keySet());
        }
        synchronized (controlChannels) {
            for (RoleDisplayKey k : controlChannels.keySet()) {
                if (!keys.contains(k)) keys.add(k);
            }
        }
        return Collections.unmodifiableList(keys);
    }

    // ------------------------------------------------------------------
    // Shutdown / close
    // ------------------------------------------------------------------

    public void shutdown() {
        shutdownSocket(negotiationSocket);
        List<RoleDisplayKey> keys = listBoundKeys();
        for (RoleDisplayKey k : keys) {
            FdChannel ch;
            synchronized (fdChannels) { ch = fdChannels.get(k); }
            if (ch != null) shutdownSocket(ch.socket);
        }
    }

    @Override
    public void close() {
        closeQuietly(negotiationSocket);
        List<FdChannel> all;
        synchronized (fdChannels) {
            all = new ArrayList<>(fdChannels.values());
            fdChannels.clear();
        }
        synchronized (controlChannels) {
            controlChannels.clear();
        }
        for (FdChannel ch : all) {
            closeQuietly(ch.socket);
        }
    }

    // ------------------------------------------------------------------
    // Device metadata
    // ------------------------------------------------------------------

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];
        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        if (negotiationSocket == null) {
            throw new IOException("No negotiation socket available to send device metadata");
        }
        FileDescriptor fd = getFileDescriptor(negotiationSocket);
        if (fd == null) {
            throw new IOException("Could not get file descriptor for device metadata transmission");
        }
        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    // ------------------------------------------------------------------
    // Handshake primitives
    // ------------------------------------------------------------------

    /** Read the 1-byte role header shared by all socket types. */
    public static int readSocketRole(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int role = in.read();
        if (role < 0) {
            throw new IOException("Connection closed before role byte received");
        }
        if (role != ROLE_VIDEO && role != ROLE_AUDIO && role != ROLE_CONTROL && role != ROLE_NEGOTIATION) {
            throw new IOException("Invalid socket role: " + role);
        }
        return role;
    }

    public static void writeSessionId(Socket socket, int sessionId) throws IOException {
        OutputStream out = socket.getOutputStream();
        writeInt32(out, sessionId);
    }

    public static int readSessionId(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        return readInt32(in);
    }

    /**
     * For ROLE_VIDEO / ROLE_AUDIO / ROLE_CONTROL the server writes the
     * displayId (4 bytes, big-endian) to the socket after accepting the
     * role socket, as an acknowledgment that the (role,displayId) was
     * routed correctly. The client reads these 4 bytes to confirm.
     */
    public static void writeDisplayId(Socket socket, int displayId) throws IOException {
        writeInt32(socket.getOutputStream(), displayId);
    }

    /** Read the client-announced displayId for a role socket. */
    public static int readDisplayId(Socket socket) throws IOException {
        return readInt32(socket.getInputStream());
    }

    private static void writeInt32(OutputStream out, int value) throws IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
        out.flush();
    }

    private static int readInt32(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) {
            throw new IOException("Connection closed before 4-byte integer received");
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static FileDescriptor getFileDescriptor(Socket socket) {
        try {
            Field implField = Socket.class.getDeclaredField("impl");
            implField.setAccessible(true);
            Object socketImpl = implField.get(socket);
            Class<?> socketImplClass = socketImpl.getClass();
            while (socketImplClass != null) {
                try {
                    Field fdField = socketImplClass.getDeclaredField("fd");
                    fdField.setAccessible(true);
                    return (FileDescriptor) fdField.get(socketImpl);
                } catch (NoSuchFieldException e) {
                    socketImplClass = socketImplClass.getSuperclass();
                }
            }
            Ln.w("getFileDescriptor: could not find 'fd' field in Socket.impl hierarchy");
        } catch (Exception e) {
            Ln.w("getFileDescriptor: reflection failed, video/audio streaming may not work", e);
        }
        return null;
    }

    private static void shutdownSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.shutdownInput();
            socket.shutdownOutput();
        } catch (IOException e) {
            Ln.w("Socket shutdown failed", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            Ln.w("Socket close failed", e);
        }
    }
}
