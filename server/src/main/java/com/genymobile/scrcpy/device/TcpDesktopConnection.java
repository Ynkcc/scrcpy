package com.genymobile.scrcpy.device;

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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class TcpDesktopConnection implements Closeable {

    public static final int ROLE_VIDEO = 0;
    public static final int ROLE_AUDIO = 1;
    public static final int ROLE_CONTROL = 2;

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static ServerSocket persistentServerSocket;

    private Socket videoSocket;
    private FileDescriptor videoFd;

    private Socket audioSocket;
    private FileDescriptor audioFd;

    private final Socket controlSocket;
    private final ControlChannel controlChannel;

    private final int sessionId;

    public TcpDesktopConnection(Socket controlSocket, int sessionId) throws IOException {
        this.controlSocket = controlSocket;
        this.sessionId = sessionId;
        this.controlChannel = controlSocket != null ? new ControlChannel(controlSocket.getInputStream(), controlSocket.getOutputStream()) : null;
    }

    public void bindVideoSocket(Socket videoSocket) throws IOException {
        if (this.videoSocket != null) {
            Ln.w("bindVideoSocket: video socket already bound, closing extra");
            videoSocket.close();
            return;
        }
        this.videoSocket = videoSocket;
        this.videoFd = getFileDescriptor(videoSocket);
        Ln.i("TcpDesktopConnection[" + sessionId + "]: video socket bound, fd=" + videoFd);
    }

    public void bindAudioSocket(Socket audioSocket) throws IOException {
        if (this.audioSocket != null) {
            Ln.w("bindAudioSocket: audio socket already bound, closing extra");
            audioSocket.close();
            return;
        }
        this.audioSocket = audioSocket;
        this.audioFd = getFileDescriptor(audioSocket);
        Ln.i("TcpDesktopConnection[" + sessionId + "]: audio socket bound, fd=" + audioFd);
    }

    public Socket getVideoSocket() {
        return videoSocket;
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }

    public int getSessionId() {
        return sessionId;
    }

    public boolean hasVideo() {
        return videoSocket != null;
    }

    public boolean hasAudio() {
        return audioSocket != null;
    }

    public boolean hasControl() {
        return controlSocket != null;
    }

    public void shutdown() {
        shutdownSocket(videoSocket);
        shutdownSocket(audioSocket);
        shutdownSocket(controlSocket);
    }

    @Override
    public void close() {
        closeQuietly(videoSocket);
        closeQuietly(audioSocket);
        closeQuietly(controlSocket);
        videoSocket = null;
        audioSocket = null;
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

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);

        Socket firstSocket = getFirstSocket();
        if (firstSocket == null) {
            throw new IOException("No socket available to send device metadata");
        }
        FileDescriptor fd = getFileDescriptor(firstSocket);
        if (fd == null) {
            throw new IOException("Could not get file descriptor for device metadata transmission");
        }
        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    private Socket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

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

    public static synchronized void initServerSocket(int scid, int customPort, String bindAddress) throws IOException {
        if (persistentServerSocket != null && !persistentServerSocket.isClosed()) {
            return;
        }
        int port = getPort(scid, customPort);
        persistentServerSocket = new ServerSocket();
        persistentServerSocket.setReuseAddress(true);
        persistentServerSocket.bind(new InetSocketAddress(bindAddress, port), 50);
        Ln.i("TcpDesktopConnection: persistent ServerSocket bound to " + bindAddress + ":" + port);
    }

    public static synchronized ServerSocket getServerSocket() {
        return persistentServerSocket;
    }

    public static synchronized void closeServerSocket() {
        if (persistentServerSocket != null) {
            try {
                persistentServerSocket.close();
                Ln.i("TcpDesktopConnection: persistent ServerSocket closed");
            } catch (IOException e) {
                Ln.w("TcpDesktopConnection: failed to close persistent ServerSocket", e);
            }
            persistentServerSocket = null;
        }
    }

    public static Socket acceptNextSocket() throws IOException {
        ServerSocket ss = persistentServerSocket;
        if (ss == null || ss.isClosed()) {
            throw new IOException("ServerSocket not initialized");
        }
        return ss.accept();
    }

    public static int readSocketRole(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int role = in.read();
        if (role < 0) {
            throw new IOException("Connection closed before role byte received");
        }
        if (role != ROLE_VIDEO && role != ROLE_AUDIO && role != ROLE_CONTROL) {
            throw new IOException("Invalid socket role: " + role);
        }
        return role;
    }

    public static void writeSessionId(Socket socket, int sessionId) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((sessionId >> 24) & 0xFF);
        out.write((sessionId >> 16) & 0xFF);
        out.write((sessionId >> 8) & 0xFF);
        out.write(sessionId & 0xFF);
        out.flush();
    }

    public static int readSessionId(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) {
            throw new IOException("Connection closed before sessionId received");
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static int getPort(int scid, int customPort) {
        if (customPort != -1) {
            return customPort;
        }
        return 27183;
    }
}
