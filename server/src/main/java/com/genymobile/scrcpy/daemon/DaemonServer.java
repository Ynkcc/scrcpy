package com.genymobile.scrcpy.daemon;

import android.os.Looper;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.net.TcpDesktopConnection;
import com.genymobile.scrcpy.daemon.net.TcpServerSocketListener;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DaemonServer {

    private static final int MAX_SESSIONS = 16;

    private final Options options;
    private final DaemonOptions daemonOptions;
    private final String[] baseArgs;

    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final VirtualDisplayRegistry registry;
    private final DisplaySurfaceBroker surfaceBroker;
    private final DaemonExitCoordinator exitCoordinator;

    private ExecutorService acceptExecutor;
    private ExecutorService clientExecutor;

    public DaemonServer(Options options, DaemonOptions daemonOptions, String[] baseArgs) {
        this.options = options;
        this.daemonOptions = daemonOptions;
        this.baseArgs = baseArgs;
        this.registry = new VirtualDisplayRegistry();
        this.surfaceBroker = new DisplaySurfaceBroker(registry);
        this.exitCoordinator = new DaemonExitCoordinator(this);
    }

    public void run() throws IOException {
        Ln.i("DaemonServer starting on port " + daemonOptions.getPort() + ", bind=" + daemonOptions.getBindAddress());

        acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "daemon-accept");
            t.setDaemon(true);
            return t;
        });
        clientExecutor = Executors.newFixedThreadPool(MAX_SESSIONS, r -> {
            Thread t = new Thread(() -> {
                Looper.prepare();
                r.run();
            }, "daemon-client");
            t.setDaemon(true);
            return t;
        });

        acceptExecutor.submit(this::acceptLoop);

        try {
            synchronized (running) {
                while (running.get()) {
                    running.wait();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        shutdown();
    }

    private void acceptLoop() {
        Ln.i("DaemonServer accept loop started");
        while (running.get()) {
            try {
                Socket socket = TcpServerSocketListener.acceptNextSocket();
                int role;
                try {
                    role = TcpDesktopConnection.readSocketRole(socket);
                } catch (IOException e) {
                    Ln.w("Failed to read socket role: " + e.getMessage());
                    socket.close();
                    continue;
                }

                switch (role) {
                    case TcpDesktopConnection.ROLE_CONTROL:
                        handleControlSocket(socket);
                        break;
                    case TcpDesktopConnection.ROLE_VIDEO:
                        handleVideoSocket(socket);
                        break;
                    case TcpDesktopConnection.ROLE_AUDIO:
                        handleAudioSocket(socket);
                        break;
                    default:
                        Ln.w("Unknown socket role: " + role);
                        socket.close();
                }
            } catch (IOException e) {
                if (running.get()) {
                    Ln.w("Accept loop error: " + e.getMessage());
                }
            }
        }
        Ln.i("DaemonServer accept loop exited");
    }

    private void handleControlSocket(Socket socket) {
        // Atomically reserve a session id in [1, Integer.MAX_VALUE]; wrap to 1
        // on overflow. getAndUpdate is atomic, unlike the previous
        // getAndIncrement + non-atomic reset-to-1 which could let two
        // concurrent callers both pick the same (possibly 0 or negative) id.
        int sessionId = nextSessionId.getAndUpdate(prev -> prev >= Integer.MAX_VALUE ? 1 : prev + 1);

        try {
            TcpDesktopConnection.writeSessionId(socket, sessionId);
        } catch (IOException e) {
            Ln.w("Failed to write sessionId: " + e.getMessage());
            try { socket.close(); } catch (IOException closeEx) {
                Ln.d("Failed to close control socket after sessionId write failure: " + closeEx.getMessage());
            }
            return;
        }

        ClientSession session;
        try {
            session = new ClientSession(socket, sessionId, options, baseArgs, registry, surfaceBroker, exitCoordinator, this);
        } catch (IOException e) {
            Ln.w("Failed to create client session: " + e.getMessage());
            try { socket.close(); } catch (IOException closeEx) {
                Ln.d("Failed to close control socket after session creation failure: " + closeEx.getMessage());
            }
            return;
        }

        sessions.put(sessionId, session);
        Ln.i("DaemonServer: new control session " + sessionId + " from " + socket.getRemoteSocketAddress());

        try {
            clientExecutor.submit(session);
        } catch (RejectedExecutionException e) {
            // Executor was shut down between accept and submit (during daemon
            // shutdown). Remove the map entry and tear down the session so the
            // socket does not leak.
            Ln.w("DaemonServer: rejected session " + sessionId + " (executor shut down?), cleaning up");
            sessions.remove(sessionId);
            session.shutdown();
        }
    }

    private void handleVideoSocket(Socket socket) {
        try {
            int sessionId = TcpDesktopConnection.readSessionId(socket);
            ClientSession session = sessions.get(sessionId);
            if (session == null) {
                Ln.w("Video socket for unknown session " + sessionId);
                socket.close();
                return;
            }
            session.onVideoSocket(socket);
        } catch (IOException e) {
            Ln.w("Failed to handle video socket: " + e.getMessage());
            try { socket.close(); } catch (IOException closeEx) {
                Ln.d("Failed to close video socket after handling error: " + closeEx.getMessage());
            }
        }
    }

    private void handleAudioSocket(Socket socket) {
        try {
            int sessionId = TcpDesktopConnection.readSessionId(socket);
            ClientSession session = sessions.get(sessionId);
            if (session == null) {
                Ln.w("Audio socket for unknown session " + sessionId);
                socket.close();
                return;
            }
            session.onAudioSocket(socket);
        } catch (IOException e) {
            Ln.w("Failed to handle audio socket: " + e.getMessage());
            try { socket.close(); } catch (IOException closeEx) {
                Ln.d("Failed to close audio socket after handling error: " + closeEx.getMessage());
            }
        }
    }

    private void shutdown() {
        Ln.i("DaemonServer shutting down, " + sessions.size() + " active sessions");

        for (ClientSession session : sessions.values()) {
            session.shutdown();
        }
        sessions.clear();

        if (registry != null) {
            registry.releaseAll();
        }

        if (acceptExecutor != null) {
            acceptExecutor.shutdown();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdown();
        }

        TcpServerSocketListener.closeServerSocket();
    }

    public void requestExit() {
        running.set(false);
        synchronized (running) {
            running.notifyAll();
        }
    }

    void removeSession(int sessionId) {
        sessions.remove(sessionId);
        Ln.i("DaemonServer: session " + sessionId + " removed");
    }
}
