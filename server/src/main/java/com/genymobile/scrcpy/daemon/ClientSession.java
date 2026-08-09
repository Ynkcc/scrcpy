package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageReader;
import com.genymobile.scrcpy.control.DeviceMessageSender;
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
 * <p>Negotiation (ROLE_NEGOTIATION) is processed directly on the session thread.
 * When the client receives success from negotiation, it establishes ROLE_CONTROL
 * and ROLE_VIDEO, which are dynamically bound to this session via callbacks.
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
    private DaemonCommandHandler scrcpyCommandHandler;
    private DaemonCommandHandler negotiationCommandHandler;
    private volatile SessionVideoController videoController;
    private ControlLoopRunner controlLoopRunner;

    public ClientSession(Socket negotiationSocket, int sessionId, Options options, String[] baseArgs,
                         VirtualDisplayRegistry registry, DisplaySurfaceBroker surfaceBroker,
                         DaemonExitCoordinator exitCoordinator, DaemonServer server) throws IOException {
        this.sessionId = sessionId;
        this.connection = new TcpDesktopConnection(negotiationSocket, sessionId);
        this.options = options;
        this.baseArgs = baseArgs;
        this.registry = registry;
        this.surfaceBroker = surfaceBroker;
        this.exitCoordinator = exitCoordinator;
        this.server = server;
    }

    @Override
    public void run() {
        Ln.i("Session[" + sessionId + "]: starting negotiation loop");
        String deviceName = Device.getDeviceName();
        try {
            connection.sendDeviceMeta(deviceName);
            Ln.i("Session[" + sessionId + "]: device meta sent via negotiation channel");
        } catch (IOException e) {
            Ln.w("Session[" + sessionId + "]: failed to send device meta: " + e.getMessage());
        }

        DeviceMessageSender negotiationSender = null;
        try {
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
                    options, baseArgs, surfaceBroker, exited::get);

            negotiationSender = new DeviceMessageSender(connection.getNegotiationChannel());
            negotiationSender.start();

            negotiationCommandHandler = new DaemonCommandHandler(
                    negotiationSender,
                    null,
                    registry,
                    exitCoordinator,
                    videoController
            );

            while (!exited.get() && !Thread.currentThread().isInterrupted()) {
                ControlMessage msg = connection.getNegotiationChannel().recv();
                if (msg == null) {
                    break;
                }
                boolean handled = negotiationCommandHandler.handle(msg);
                if (!handled) {
                    Ln.w("Session[" + sessionId + "]: unhandled message type in negotiation: " + msg.getType());
                }
            }
        } catch (Throwable t) {
            if (!exited.get()) {
                Ln.e("Session[" + sessionId + "]: error in negotiation loop", t);
            }
        } finally {
            if (negotiationSender != null) {
                negotiationSender.stop();
                try {
                    negotiationSender.join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (negotiationCommandHandler != null) {
                negotiationCommandHandler.close();
            }
            cleanup();
            Ln.i("Session[" + sessionId + "]: closed");
            if (exitCoordinator.isExitRequested()) {
                Ln.i("Session[" + sessionId + "]: Exit coordinator requested shutdown, exiting.");
            }
        }
    }

    public void onControlSocket(Socket socket) {
        if (exited.get()) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        try {
            connection.bindControlSocket(socket);
            Ln.i("Session[" + sessionId + "]: control socket bound, launching scrcpy controller");

            controller = new Controller(connection.getControlChannel(), null, options);
            scrcpyCommandHandler = new DaemonCommandHandler(controller, registry, exitCoordinator, videoController);
            controller.setControlMessageExtension(scrcpyCommandHandler);

            if (videoController != null) {
                videoController.setController(controller);
            }

            controlLoopRunner = new ControlLoopRunner(sessionId);
            controlLoopRunner.start(controller, fatalError -> {
                Ln.i("Session[" + sessionId + "]: scrcpy controller terminated, fatal=" + fatalError);
                controller.stop();
            });
        } catch (Throwable t) {
            Ln.e("Session[" + sessionId + "]: failed to initialize scrcpy controller", t);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void onVideoSocket(Socket socket) {
        SessionVideoController vc = this.videoController;
        if (vc != null) {
            vc.bindVideoSocket(socket);
        } else {
            try {
                socket.close();
            } catch (IOException ignored) {}
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

            if (scrcpyCommandHandler != null) {
                scrcpyCommandHandler.close();
            }

            if (controlLoopRunner != null) {
                try {
                    controlLoopRunner.join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            connection.shutdown();
            connection.close();
            if (server != null) {
                server.removeSession(sessionId);
            }
        }
    }
}
