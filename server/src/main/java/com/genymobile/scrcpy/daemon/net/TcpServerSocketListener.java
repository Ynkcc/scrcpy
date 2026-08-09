package com.genymobile.scrcpy.daemon.net;

import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class TcpServerSocketListener {

    private static ServerSocket persistentServerSocket;

    private TcpServerSocketListener() {
    }

    public static synchronized void initServerSocket(int scid, int customPort, String bindAddress) throws IOException {
        if (persistentServerSocket != null && !persistentServerSocket.isClosed()) {
            return;
        }
        int port = getPort(scid, customPort);
        persistentServerSocket = new ServerSocket();
        persistentServerSocket.setReuseAddress(true);
        persistentServerSocket.bind(new InetSocketAddress(bindAddress, port), 50);
        Ln.i("TcpServerSocketListener: persistent ServerSocket bound to " + bindAddress + ":" + port);
    }

    public static synchronized ServerSocket getServerSocket() {
        return persistentServerSocket;
    }

    public static synchronized void closeServerSocket() {
        if (persistentServerSocket != null) {
            try {
                persistentServerSocket.close();
                Ln.i("TcpServerSocketListener: persistent ServerSocket closed");
            } catch (IOException e) {
                Ln.w("TcpServerSocketListener: failed to close persistent ServerSocket", e);
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

    private static int getPort(int scid, int customPort) {
        if (customPort != -1) {
            return customPort;
        }
        return 27183;
    }
}
