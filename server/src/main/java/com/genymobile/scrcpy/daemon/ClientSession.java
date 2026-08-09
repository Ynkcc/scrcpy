package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.daemon.control.DaemonCommandHandler;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.net.TcpDesktopConnection;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-client session orchestrator.
 *
 * <p>Formerly a god-object (306 lines) that also owned the video pipeline and control loop
 * internals. After the refactor it only coordinates lifecycle: it wires together the
 * {@link Controller}, {@link DaemonCommandHandler} and {@link SessionVideoController}, runs
 * the control loop via {@link ControlLoopRunner}, and performs ordered cleanup. Video
 * capture/encode ownership lives in {@link SessionVideoController}; upstream {@code video/}
 * construction is isolated in {@link DaemonVideoPipeline}.
 */
public final class ClientSession implements Runnable {

    private final int sessionId;
    private final TcpDesktopConnection connection;
    private final Options options;
    private final String[] baseArgs;
    private final VirtualDisplayRegistry registry;
    private final DisplaySurfaceBroker surfaceBroker;
    private final DaemonExitCoordinator exitCoordinator;
    private final DaemonServer server;

    private final AtomicBoolean exited = new AtomicBoolean(false);

    private Controller controller;
    private DaemonCommandHandler daemonCommandHandler;
    private SessionVideoController videoController;
    private ControlLoopRunner controlLoopRunner;

    public ClientSession(Socket controlSocket, int sessionId, Options options, String[] baseArgs,
                         VirtualDisplayRegistry registry, DisplaySurfaceBroker surfaceBroker,
                         DaemonExitCoordinator exitCoordinator, DaemonServer server) throws IOException {
        this.sessionId = sessionId;
        this.connection = new TcpDesktopConnection(controlSocket, sessionId);
        this.options = options;
        this.baseArgs = baseArgs;
        this.registry = registry;
        this.surfaceBroker = surfaceBroker;
        this.exitCoordinator = exitCoordinator;
        this.server = server;
    }

    @Override
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
            Options sessionOptions = options;
            controller = new Controller(connection.getControlChannel(), null, sessionOptions);
            Ln.i("Session[" + sessionId + "]: Controller created, displayId=" + sessionOptions.getDisplayId());

            // Video lifecycle owner. Wired with the session-exit flag as a BooleanSupplier so it
            // can guard startVideoStream without owning session state. A thin adapter over the
            // TcpDesktopConnection exposes only the video-fd surface the controller needs, so
            // net/TcpDesktopConnection stays untouched.
            videoController = new SessionVideoController(
                    sessionId,
                    new SessionVideoController.TcpVideoBinding() {
                        @Override
                        public java.io.FileDescriptor getVideoFd() {
                            return connection.getVideoFd();
                        }

                        @Override
                        public boolean hasVideo() {
                            return connection.hasVideo();
                        }

                        @Override
                        public void bindVideoSocket(Socket socket) throws IOException {
                            connection.bindVideoSocket(socket);
                        }
                    },
                    sessionOptions, baseArgs, surfaceBroker, exited::get);
            videoController.setController(controller);

            daemonCommandHandler = new DaemonCommandHandler(controller, registry, exitCoordinator, videoController);
            controller.setControlMessageExtension(daemonCommandHandler);
            Ln.i("Session[" + sessionId + "]: DaemonCommandHandler extension injected");

            controlLoopRunner = new ControlLoopRunner(sessionId);
            controlLoopRunner.start(controller, fatalError -> {
                Ln.i("Session[" + sessionId + "]: controller terminated, fatal=" + fatalError);
                controller.stop();
            });
            controlLoopRunner.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Ln.e("Session[" + sessionId + "]: error", t);
        } finally {
            cleanup();
            Ln.i("Session[" + sessionId + "]: closed");
            if (exitCoordinator.isExitRequested()) {
                Ln.i("Session[" + sessionId + "]: Exit coordinator requested shutdown, exiting.");
            }
        }
    }

    public void onVideoSocket(Socket socket) {
        if (videoController != null) {
            videoController.bindVideoSocket(socket);
        } else {
            try { socket.close(); } catch (IOException ignored) {}
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
            if (videoController != null) {
                videoController.stopVideoInternal();
            }

            if (controller != null) {
                controller.stop();
                try {
                    controller.join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            if (daemonCommandHandler != null) {
                daemonCommandHandler.close();
            }

            connection.shutdown();
            connection.close();
            if (server != null) {
                server.removeSession(sessionId);
            }
        }
    }
}
