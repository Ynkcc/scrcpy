package com.genymobile.scrcpy.daemon.net;

import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TcpServerSocketListener {

    private static final List<ServerSocket> serverSockets = new ArrayList<>();
    private static final LinkedBlockingQueue<Socket> acceptedSockets = new LinkedBlockingQueue<>();
    private static final List<Thread> acceptThreads = new ArrayList<>();
    private static volatile boolean closed = false;

    private TcpServerSocketListener() {
    }

    public static synchronized void initServerSocket(int scid, int customPort, String bindAddress) throws IOException {
        if (!serverSockets.isEmpty()) {
            boolean allAlive = true;
            for (ServerSocket ss : serverSockets) {
                if (ss.isClosed()) {
                    allAlive = false;
                    break;
                }
            }
            if (allAlive) {
                return;
            }
        }
        closeServerSocket();
        closed = false;
        int port = getPort(scid, customPort);

        List<String> addressesToBind = new ArrayList<>();
        if (bindAddress != null && !bindAddress.isEmpty()) {
            addressesToBind.add(bindAddress);
        } else {
            addressesToBind.add("127.0.0.1");
        }

        // 如果用户指定的地址不是 127.0.0.1 / localhost / 0.0.0.0，则始终追加 127.0.0.1
        if (bindAddress != null && !bindAddress.equals("127.0.0.1") 
                && !bindAddress.equalsIgnoreCase("localhost") 
                && !bindAddress.equals("0.0.0.0")) {
            addressesToBind.add("127.0.0.1");
        }

        for (String addr : addressesToBind) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(addr, port), 50);
                serverSockets.add(ss);
                Ln.i("TcpServerSocketListener: ServerSocket bound to " + addr + ":" + port);

                // 启动接收线程
                Thread thread = new Thread(() -> {
                    while (!closed) {
                        try {
                            Socket s = ss.accept();
                            acceptedSockets.put(s);
                        } catch (IOException e) {
                            if (!closed) {
                                Ln.w("ServerSocket accept failed on " + addr + ": " + e.getMessage());
                            }
                            break;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }, "accept-thread-" + addr);
                thread.setDaemon(true);
                thread.start();
                acceptThreads.add(thread);
            } catch (IOException e) {
                Ln.e("Failed to bind to " + addr + ":" + port, e);
                if (addressesToBind.size() == 1 || addr.equals(bindAddress)) {
                    throw e; // 主监听地址绑定失败则抛出异常
                }
            }
        }
    }

    public static synchronized ServerSocket getServerSocket() {
        return serverSockets.isEmpty() ? null : serverSockets.get(0);
    }

    public static synchronized void closeServerSocket() {
        closed = true;
        for (ServerSocket ss : serverSockets) {
            try {
                ss.close();
                Ln.i("TcpServerSocketListener: ServerSocket closed: " + ss.getLocalSocketAddress());
            } catch (IOException e) {
                Ln.w("TcpServerSocketListener: failed to close ServerSocket", e);
            }
        }
        serverSockets.clear();
        for (Thread t : acceptThreads) {
            t.interrupt();
        }
        acceptThreads.clear();

        List<Socket> remaining = new ArrayList<>();
        acceptedSockets.drainTo(remaining);
        for (Socket s : remaining) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
    }

    public static Socket acceptNextSocket() throws IOException {
        while (!closed) {
            try {
                Socket s = acceptedSockets.poll(200, TimeUnit.MILLISECONDS);
                if (s != null) {
                    return s;
                }
                if (closed) {
                    throw new IOException("ServerSocket closed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Accept interrupted", e);
            }
        }
        throw new IOException("ServerSocket closed");
    }

    private static int getPort(int scid, int customPort) {
        if (customPort != -1) {
            return customPort;
        }
        return 27183;
    }
}
