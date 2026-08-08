package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class TcpDesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private final Socket videoSocket;
    private final FileDescriptor videoFd;

    private final Socket audioSocket;
    private final FileDescriptor audioFd;

    private final Socket controlSocket;
    private final ControlChannel controlChannel;

    private TcpDesktopConnection(Socket videoSocket, Socket audioSocket, Socket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;

        videoFd = videoSocket != null ? getFileDescriptor(videoSocket) : null;
        audioFd = audioSocket != null ? getFileDescriptor(audioSocket) : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket.getInputStream(), controlSocket.getOutputStream()) : null;
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

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        return socket;
    }

    private static int getPort(int scid, int customPort) {
        if (customPort != -1) {
            return customPort;
        }
        return 27183 + (scid != -1 ? scid : 0);
    }

    public static TcpDesktopConnection open(int scid, int customPort, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        int port = getPort(scid, customPort);

        Socket videoSocket = null;
        Socket audioSocket = null;
        Socket controlSocket = null;
        try {
            if (tunnelForward) {
                ServerSocket serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 50);
                try {
                    if (video) {
                        videoSocket = serverSocket.accept();
                        if (sendDummyByte) {
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = serverSocket.accept();
                        if (sendDummyByte) {
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = serverSocket.accept();
                        if (sendDummyByte) {
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                } finally {
                    serverSocket.close();
                }
            } else {
                if (video) {
                    videoSocket = connect(port);
                }
                if (audio) {
                    audioSocket = connect(port);
                }
                if (control) {
                    controlSocket = connect(port);
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(videoSocket);
            closeQuietly(audioSocket);
            closeQuietly(controlSocket);
            throw e;
        }

        return new TcpDesktopConnection(videoSocket, audioSocket, controlSocket);
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

    public void shutdown() {
        shutdownSocket(videoSocket);
        shutdownSocket(audioSocket);
        shutdownSocket(controlSocket);
    }

    public void close() {
        closeQuietly(videoSocket);
        closeQuietly(audioSocket);
        closeQuietly(controlSocket);
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

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
