package com.genymobile.scrcpy;

import android.os.Looper;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.DeviceMessageSender;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.TcpDesktopConnection;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DaemonServer {

    private static final int MAX_SESSIONS = 16;

    private final Options options;
    private final DaemonOptions daemonOptions;

    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ExecutorService acceptExecutor;
    private ExecutorService clientExecutor;

    public DaemonServer(Options options, DaemonOptions daemonOptions) {
        this.options = options;
        this.daemonOptions = daemonOptions;
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
                Socket socket = TcpDesktopConnection.acceptNextSocket();
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
        int sessionId = nextSessionId.getAndIncrement();
        if (sessionId <= 0) {
            sessionId = 1;
        }

        try {
            TcpDesktopConnection.writeSessionId(socket, sessionId);
        } catch (IOException e) {
            Ln.w("Failed to write sessionId: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }

        ClientSession session;
        try {
            session = new ClientSession(socket, sessionId);
        } catch (IOException e) {
            Ln.w("Failed to create client session: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }

        sessions.put(sessionId, session);
        Ln.i("DaemonServer: new control session " + sessionId + " from " + socket.getRemoteSocketAddress());

        clientExecutor.submit(session::run);
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
            try { socket.close(); } catch (IOException ignored) {}
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
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void shutdown() {
        Ln.i("DaemonServer shutting down, " + sessions.size() + " active sessions");

        for (ClientSession session : sessions.values()) {
            session.shutdown();
        }
        sessions.clear();

        if (acceptExecutor != null) {
            acceptExecutor.shutdown();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdown();
        }

        TcpDesktopConnection.closeServerSocket();
    }

    public void requestExit() {
        running.set(false);
        synchronized (running) {
            running.notifyAll();
        }
    }

    public Options getOptions() {
        return options;
    }

    public DaemonOptions getDaemonOptions() {
        return daemonOptions;
    }

    public class ClientSession implements VideoController {
        private final int sessionId;
        private final TcpDesktopConnection connection;
        private final ControlChannel controlChannel;
        private final DeviceMessageSender sender;

        private Controller controller;
        private SurfaceEncoder surfaceEncoder;
        private SurfaceCapture surfaceCapture;
        private Streamer videoStreamer;
        private Options sessionOptions;

        private final AtomicBoolean videoStarted = new AtomicBoolean(false);
        private final AtomicBoolean exited = new AtomicBoolean(false);
        private Thread videoThread;

        public ClientSession(Socket controlSocket, int sessionId) throws IOException {
            this.sessionId = sessionId;
            this.connection = new TcpDesktopConnection(controlSocket, sessionId);
            this.controlChannel = connection.getControlChannel();
            this.sender = new DeviceMessageSender(controlChannel);
        }

        public void run() {
            Ln.i("Session[" + sessionId + "]: starting");
            String deviceName = Device.getDeviceName();
            try {
                connection.sendDeviceMeta(deviceName);
                Ln.i("Session[" + sessionId + "]: device meta sent");
            } catch (IOException e) {
                Ln.w("Session[" + sessionId + "]: failed to send device meta: " + e.getMessage());
            }

            try {
                sessionOptions = getSessionOptions();
                controller = new Controller(controlChannel, null, sessionOptions);
                Ln.i("Session[" + sessionId + "]: Controller created, displayId=" + sessionOptions.getDisplayId());
                controller.getDaemonCommandHandler().setVideoController(this);
                Ln.i("Session[" + sessionId + "]: VideoController set, starting controller thread");

                Thread ctrlThread = new Thread(() -> {
                    try {
                        Ln.i("Session[" + sessionId + "]: calling controller.start()");
                        controller.start(fatalError -> {
                            Ln.i("Session[" + sessionId + "]: controller terminated, fatal=" + fatalError);
                        });
                        Ln.i("Session[" + sessionId + "]: controller.start() returned, joining...");
                        controller.join();
                        Ln.i("Session[" + sessionId + "]: controller.join() returned");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        Ln.e("Session[" + sessionId + "]: ctrlThread error", t);
                    }
                }, "ctrl-" + sessionId);
                ctrlThread.setDaemon(true);
                ctrlThread.start();

                ctrlThread.join();
                Ln.i("Session[" + sessionId + "]: ctrlThread.join() returned");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Ln.e("Session[" + sessionId + "]: error", t);
            } finally {
                cleanup();
                sessions.remove(sessionId);
                Ln.i("Session[" + sessionId + "]: closed");

                if (DaemonManager.getInstance().isExitDaemonRequested()) {
                    requestExit();
                }
            }
        }

        private Options getSessionOptions() {
            int targetDisplayId = DaemonManager.getInstance().getTargetDisplayId();
            if (targetDisplayId != 0) {
                return options.copyWithDisplayId(targetDisplayId);
            }
            return options;
        }

        @Override
        public synchronized boolean startVideoStream(int displayId) {
            if (videoStarted.get()) {
                Ln.w("Session[" + sessionId + "]: video already started for display " + displayId);
                return false;
            }

            Ln.i("Session[" + sessionId + "]: start video stream for displayId=" + displayId);

            try {
                ensureVideoFdReady();

                Options captureOptions = options.copyWithDisplayId(displayId);
                videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(),
                        options.getSendStreamMeta(), options.getSendFrameMeta());

                surfaceCapture = new ScreenCapture(
                        controller != null ? controller : (id, pm) -> {},
                        captureOptions);
                surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoStreamer, captureOptions);

                if (controller != null) {
                    controller.setSurfaceCapture(surfaceCapture);
                }

                videoStarted.set(true);

                videoThread = new Thread(() -> {
                    try {
                        surfaceEncoder.start(fatalError -> {
                            Ln.i("Session[" + sessionId + "]: video encoder terminated, fatal=" + fatalError);
                            videoStarted.set(false);
                        });
                        surfaceEncoder.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "video-" + sessionId + "-" + displayId);
                videoThread.setDaemon(true);
                videoThread.start();

                Ln.i("Session[" + sessionId + "]: video stream started for display " + displayId);
                return true;
            } catch (Exception e) {
                Ln.e("Session[" + sessionId + "]: failed to start video stream", e);
                videoStarted.set(false);
                stopVideoInternal();
                return false;
            }
        }

        private void ensureVideoFdReady() throws IOException, InterruptedException {
            if (connection.hasVideo() && connection.getVideoFd() != null) {
                return;
            }

            Ln.i("Session[" + sessionId + "]: waiting for video socket...");
            int waitCount = 0;
            while (!connection.hasVideo() && waitCount < 50) {
                Thread.sleep(200);
                waitCount++;
            }

            if (!connection.hasVideo()) {
                throw new IOException("Video socket not connected within timeout");
            }
            if (connection.getVideoFd() == null) {
                throw new IOException("Video socket has no valid file descriptor");
            }
            Ln.i("Session[" + sessionId + "]: video socket ready");
        }

        @Override
        public synchronized boolean stopVideoStream() {
            if (!videoStarted.get()) {
                Ln.w("Session[" + sessionId + "]: video not started");
                return false;
            }
            stopVideoInternal();
            return true;
        }

        private void stopVideoInternal() {
            videoStarted.set(false);
            if (surfaceEncoder != null) {
                surfaceEncoder.stop();
            }
            if (surfaceCapture != null) {
                surfaceCapture.release();
            }
            surfaceEncoder = null;
            surfaceCapture = null;
            videoStreamer = null;
            Ln.i("Session[" + sessionId + "]: video stream stopped");
        }

        @Override
        public boolean isVideoStarted() {
            return videoStarted.get();
        }

        public void onVideoSocket(Socket socket) {
            try {
                connection.bindVideoSocket(socket);
            } catch (IOException e) {
                Ln.e("Session[" + sessionId + "]: failed to bind video socket", e);
            }
        }

        public void onAudioSocket(Socket socket) {
            try {
                connection.bindAudioSocket(socket);
            } catch (IOException e) {
                Ln.e("Session[" + sessionId + "]: failed to bind audio socket", e);
            }
        }

        public int getSessionId() {
            return sessionId;
        }

        public void shutdown() {
            exited.set(true);
            cleanup();
        }

        private void cleanup() {
            if (exited.compareAndSet(false, true)) {
                stopVideoInternal();

                if (controller != null) {
                    controller.stop();
                    try {
                        controller.join();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }

                connection.shutdown();
                connection.close();
            }
        }
    }
}
