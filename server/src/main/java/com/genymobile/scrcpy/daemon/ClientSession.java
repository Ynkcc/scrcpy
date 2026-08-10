package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.DeviceMessageSender;
import com.genymobile.scrcpy.daemon.control.DaemonCommandHandler;
import com.genymobile.scrcpy.daemon.control.DaemonControlMessage;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.net.TcpDesktopConnection;
import com.genymobile.scrcpy.daemon.video.FrameBroadcasterRegistry;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-client session orchestrator — now with per-session multi-display role
 * sockets.
 *
 * <p><b>Protocol phases</b> (enforced server-side, no backward compatibility):
 * <pre>
 *   INIT       — ROLE_NEGOTIATION socket just opened; sessionId assigned,
 *                device meta sent. Only CONFIGURE_SESSION is accepted.
 *   CONFIGURED — client sent CONFIGURE_SESSION with optionsKv + rolesEntries
 *                (or legacy rolesMask). Role sockets (ROLE_VIDEO/ROLE_AUDIO/
 *                ROLE_CONTROL) for a (role, displayId) declared in
 *                rolesEntries are now accepted. The same role can be opened
 *                multiple times for different displays.
 *   CLOSED     — session tearing down; everything rejected.
 * </pre>
 */
public final class ClientSession implements Runnable, SessionConfigurator {

    private static final int PHASE_INIT = 0;
    private static final int PHASE_CONFIGURED = 1;
    private static final int PHASE_CLOSED = 2;

    private final int sessionId;
    private final TcpDesktopConnection connection;
    private final Options options;
    private final String[] baseArgs;
    private final VirtualDisplayRegistry registry;
    private final DisplaySurfaceBroker surfaceBroker;
    private final DaemonExitCoordinator exitCoordinator;
    private final DaemonServer server;
    private final FrameBroadcasterRegistry broadcasterRegistry;

    private volatile Options sessionOptions;
    private volatile String[] sessionArgs;

    /** Legacy 3-bit role-type mask; kept for fallback. */
    private volatile int declaredRolesMask = 0;
    /** Explicit (role, displayId) declarations from CONFIGURE_SESSION. */
    private volatile Set<TcpDesktopConnection.RoleDisplayKey> declaredRoleKeys = Collections.emptySet();

    private volatile int phase = PHASE_INIT;
    private final Object phaseLock = new Object();
    private final AtomicBoolean exited = new AtomicBoolean(false);

    // Per-(displayId) video controllers. Multiple displays → multiple instances.
    private final Map<Integer, SessionVideoController> videoControllers = new HashMap<>();

    // Per-(displayId) controllers and control-loop runners.
    private final Map<Integer, Controller> controllers = new HashMap<>();
    private final Map<Integer, ControlLoopRunner> controlLoopRunners = new HashMap<>();

    private volatile DaemonCommandHandler negotiationCommandHandler;

    public ClientSession(Socket negotiationSocket, int sessionId, Options options, String[] baseArgs,
                         VirtualDisplayRegistry registry, DisplaySurfaceBroker surfaceBroker,
                         DaemonExitCoordinator exitCoordinator, DaemonServer server,
                         FrameBroadcasterRegistry broadcasterRegistry) throws IOException {
        this.sessionId = sessionId;
        this.connection = new TcpDesktopConnection(negotiationSocket, sessionId);
        this.options = options;
        this.baseArgs = baseArgs;
        this.sessionOptions = options;
        this.sessionArgs = baseArgs != null ? baseArgs.clone() : new String[0];
        this.registry = registry;
        this.surfaceBroker = surfaceBroker;
        this.exitCoordinator = exitCoordinator;
        this.server = server;
        this.broadcasterRegistry = broadcasterRegistry;
    }

    @Override
    public void run() {
        Ln.i("Session[" + sessionId + "]: starting negotiation loop (phase=INIT)");
        String deviceName = Device.getDeviceName();
        try {
            connection.sendDeviceMeta(deviceName);
            Ln.i("Session[" + sessionId + "]: device meta sent via negotiation channel");
        } catch (IOException e) {
            Ln.w("Session[" + sessionId + "]: failed to send device meta: " + e.getMessage());
        }

        DeviceMessageSender negotiationSender = null;
        try {
            negotiationSender = new DeviceMessageSender(connection.getNegotiationChannel());
            negotiationSender.start();

            negotiationCommandHandler = new DaemonCommandHandler(
                    negotiationSender, null, registry, exitCoordinator, null, this, broadcasterRegistry, sessionId);

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

    // -----------------------------------------------------------------------
    // SessionConfigurator
    // -----------------------------------------------------------------------

    @Override
    public void configure(String optionsKv, int legacyRolesMask,
                          List<DaemonControlMessage.RoleEntry> rolesEntries) throws Exception {
        if (exited.get() || phase == PHASE_CLOSED) {
            throw new RuntimeException("Session already exited");
        }
        synchronized (videoControllers) {
            if (!videoControllers.isEmpty()) {
                throw new RuntimeException("Cannot configure session after video streams started");
            }
        }

        String[] merged = DaemonArgs.mergeOptions(baseArgs, optionsKv);
        Options parsed = Options.parse(merged);

        this.sessionArgs = merged;
        this.sessionOptions = parsed;
        this.declaredRolesMask = legacyRolesMask;

        if (rolesEntries != null && !rolesEntries.isEmpty()) {
            Set<TcpDesktopConnection.RoleDisplayKey> keys = new HashSet<>();
            for (DaemonControlMessage.RoleEntry e : rolesEntries) {
                keys.add(new TcpDesktopConnection.RoleDisplayKey(e.role, e.displayId));
            }
            this.declaredRoleKeys = Collections.unmodifiableSet(keys);
            // Rebuild a legacy mask summarizing which role *types* appear.
            int combinedMask = 0;
            for (TcpDesktopConnection.RoleDisplayKey k : keys) {
                combinedMask |= (1 << k.role);
            }
            // Legacy mask only matters when entries are absent; still store for logs.
            if (legacyRolesMask == 0) this.declaredRolesMask = combinedMask;
        } else {
            this.declaredRoleKeys = Collections.emptySet();
        }

        // Propagate session options to any existing SessionVideoController
        // (none should exist yet; kept for forward safety).
        synchronized (videoControllers) {
            for (SessionVideoController vc : videoControllers.values()) {
                vc.setSessionOptions(parsed, merged);
            }
        }

        synchronized (phaseLock) {
            if (phase == PHASE_INIT) {
                phase = PHASE_CONFIGURED;
                phaseLock.notifyAll();
            }
        }
        Ln.i("Session[" + sessionId + "]: CONFIGURE_SESSION applied — phase=CONFIGURED"
                + ", rolesMask=0b" + Integer.toBinaryString(this.declaredRolesMask)
                + ", rolesEntries=" + this.declaredRoleKeys.size()
                + ", " + Math.max(0, merged.length - 1) + " option keys");
    }

    // -----------------------------------------------------------------------
    // Role-socket gates — reject unless CONFIGURED + declared.
    // -----------------------------------------------------------------------

    private boolean acceptRoleSocket(int role, int displayId) {
        if (phase == PHASE_INIT) {
            synchronized (phaseLock) {
                long timeout = 2000;
                long start = System.currentTimeMillis();
                while (phase == PHASE_INIT && (System.currentTimeMillis() - start) < timeout) {
                    try {
                        phaseLock.wait(timeout - (System.currentTimeMillis() - start));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (phase != PHASE_CONFIGURED) {
            Ln.w("Session[" + sessionId + "]: rejecting role socket — phase="
                    + phaseName(phase) + " (must complete CONFIGURE_SESSION first)");
            return false;
        }
        Set<TcpDesktopConnection.RoleDisplayKey> keys = declaredRoleKeys;
        if (!keys.isEmpty()) {
            // Multi-display mode: require exact (role,displayId) declaration.
            if (!keys.contains(new TcpDesktopConnection.RoleDisplayKey(role, displayId))) {
                Ln.w("Session[" + sessionId + "]: rejecting role socket "
                        + new TcpDesktopConnection.RoleDisplayKey(role, displayId)
                        + " — not declared in CONFIGURE_SESSION rolesEntries");
                return false;
            }
            return true;
        }
        // Fallback: legacy 3-bit mask (0 = allow any declared role type).
        int bit = 1 << role;
        if (declaredRolesMask != 0 && (declaredRolesMask & bit) == 0) {
            Ln.w("Session[" + sessionId + "]: rejecting role socket bit 0b"
                    + Integer.toBinaryString(bit) + " — not declared in CONFIGURE_SESSION (mask=0b"
                    + Integer.toBinaryString(declaredRolesMask) + ")");
            return false;
        }
        return true;
    }

    private static String phaseName(int phase) {
        switch (phase) {
            case PHASE_INIT: return "INIT";
            case PHASE_CONFIGURED: return "CONFIGURED";
            case PHASE_CLOSED: return "CLOSED";
            default: return "UNKNOWN(" + phase + ")";
        }
    }

    // -----------------------------------------------------------------------
    // Role socket bindings — now per-displayId.
    // -----------------------------------------------------------------------

    public void onControlSocket(int displayId, Socket socket) {
        if (exited.get() || !acceptRoleSocket(TcpDesktopConnection.ROLE_CONTROL, displayId)) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        try {
            // Acknowledge: server sends the displayId back so client can confirm routing.
            TcpDesktopConnection.writeDisplayId(socket, displayId);

            com.genymobile.scrcpy.control.ControlChannel ch = connection.bindControlSocket(displayId, socket);
            Ln.i("Session[" + sessionId + "]: control socket bound for displayId=" + displayId
                    + ", launching scrcpy controller");

            Controller controller;
            synchronized (controllers) {
                Controller stale = controllers.remove(displayId);
                if (stale != null) {
                    stale.stop();
                    ControlLoopRunner sr = controlLoopRunners.remove(displayId);
                    if (sr != null) {
                        try { sr.join(); } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                controller = new Controller(ch, null, sessionOptions, displayId);

                // 职责去重: swallow daemon commands on control channel; only
                // the negotiation channel carries them.
                controller.setControlMessageExtension(msg -> {
                    Ln.w("Session[" + sessionId + "]: daemon command type " + msg.getType()
                            + " rejected on control channel (displayId=" + displayId
                            + ") — send it on the negotiation channel instead");
                    return true;
                });

                controllers.put(displayId, controller);
            }

            // Wire any displayId-matching video controller to this controller
            // (needed by encoder reset paths that consult the scrcpy Controller).
            synchronized (videoControllers) {
                SessionVideoController vc = videoControllers.get(displayId);
                if (vc != null) {
                    vc.setController(controller);
                }
            }

            // Broadcast into the negotiation handler — it now exposes all
            // display controllers via hasControllerFor(any) semantics so
            // RESIZE_VIRTUAL_DISPLAY can kick the shared broadcaster regardless
            // of which display's controller is currently wired as "primary".
            if (negotiationCommandHandler != null) {
                negotiationCommandHandler.updateController(controller);
            }

            ControlLoopRunner runner = new ControlLoopRunner(sessionId);
            synchronized (controlLoopRunners) {
                controlLoopRunners.put(displayId, runner);
            }
            final Controller finalCtrl = controller;
            runner.start(controller, fatalError -> {
                Ln.i("Session[" + sessionId + "]: scrcpy controller terminated for displayId="
                        + displayId + ", fatal=" + fatalError);
                finalCtrl.stop();
            });
        } catch (Throwable t) {
            Ln.e("Session[" + sessionId + "]: failed to initialize scrcpy controller displayId=" + displayId, t);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void onVideoSocket(int displayId, Socket socket) {
        if (exited.get() || !acceptRoleSocket(TcpDesktopConnection.ROLE_VIDEO, displayId)) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        SessionVideoController vc;
        synchronized (videoControllers) {
            vc = videoControllers.get(displayId);
            if (vc == null) {
                final int vcDisplayId = displayId;
                vc = new SessionVideoController(
                        sessionId,
                        vcDisplayId,
                        new TcpVideoBindingAdapter(connection, vcDisplayId),
                        sessionOptions, sessionArgs, surfaceBroker, broadcasterRegistry,
                        registry, exited::get);
                videoControllers.put(displayId, vc);
                // If a controller for this display already exists, wire it.
                Controller ctrl;
                synchronized (controllers) { ctrl = controllers.get(vcDisplayId); }
                if (ctrl != null) vc.setController(ctrl);
            }
        }
        try {
            TcpDesktopConnection.writeDisplayId(socket, displayId);
        } catch (IOException e) {
            Ln.w("Session[" + sessionId + "]: failed to write displayId ack for video socket: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        vc.bindVideoSocket(socket);
    }

    public void onAudioSocket(int displayId, Socket socket) {
        if (exited.get() || !acceptRoleSocket(TcpDesktopConnection.ROLE_AUDIO, displayId)) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        try {
            TcpDesktopConnection.writeDisplayId(socket, displayId);
            connection.bindAudioSocket(displayId, socket);
        } catch (IOException e) {
            Ln.e("Session[" + sessionId + "]: failed to bind audio socket displayId=" + displayId, e);
        }
    }

    // Adapts the per-display FdChannel to SessionVideoController.TcpVideoBinding.
    private static final class TcpVideoBindingAdapter implements SessionVideoController.TcpVideoBinding {
        private final TcpDesktopConnection conn;
        private final int displayId;

        TcpVideoBindingAdapter(TcpDesktopConnection conn, int displayId) {
            this.conn = conn;
            this.displayId = displayId;
        }

        @Override
        public java.io.FileDescriptor getVideoFd() {
            TcpDesktopConnection.FdChannel ch = conn.getVideoChannel(displayId);
            return ch != null ? ch.fd : null;
        }

        @Override
        public boolean hasVideo() {
            return conn.getVideoChannel(displayId) != null;
        }

        @Override
        public void bindVideoSocket(Socket socket) throws IOException {
            conn.bindVideoSocket(displayId, socket);
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
            synchronized (phaseLock) {
                phase = PHASE_CLOSED;
            }
        }

        // Step 3: release all VD references held by this session.
        registry.releaseSession(sessionId);
        // Step 4: release all video subscribers for this session.
        broadcasterRegistry.releaseSession(sessionId);

        // Stop all per-display video controllers (releases subscribers a
        // second time — registry deduplicates repeated releaseSubscriber calls
        // for the same (displayId, sessionId, subscriber) so this is safe).
        List<SessionVideoController> videoCopy;
        synchronized (videoControllers) {
            videoCopy = new ArrayList<>(videoControllers.values());
            videoControllers.clear();
        }
        for (SessionVideoController vc : videoCopy) {
            vc.stopVideoInternal();
        }

        // Stop all per-display controllers and their loops.
        List<Controller> ctrlCopy;
        List<ControlLoopRunner> runnerCopy;
        synchronized (controllers) {
            ctrlCopy = new ArrayList<>(controllers.values());
            controllers.clear();
        }
        synchronized (controlLoopRunners) {
            runnerCopy = new ArrayList<>(controlLoopRunners.values());
            controlLoopRunners.clear();
        }
        for (Controller c : ctrlCopy) {
            c.stop();
        }
        for (ControlLoopRunner r : runnerCopy) {
            try {
                r.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        for (Controller c : ctrlCopy) {
            try {
                c.join();
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
