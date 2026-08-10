package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.video.FrameBroadcasterRegistry;
import com.genymobile.scrcpy.daemon.video.VideoSubscriber;
import com.genymobile.scrcpy.util.Ln;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Per-(sessionId, displayId) video controller — a thin client of the shared
 * {@link FrameBroadcasterRegistry}.
 *
 * <p>With the removal of {@code TYPE_START_VIDEO_STREAM} /
 * {@code TYPE_STOP_VIDEO_STREAM} the lifecycle of a video stream is now tied
 * directly to the lifecycle of the (ROLE_VIDEO, displayId) socket: binding
 * the socket automatically:
 *   1. {@code registry.acquire(displayId, sessionId)} — keeps the VD alive
 *      for the duration of the stream even if the creator session
 *      disconnects;
 *   2. {@code broadcasterRegistry.acquireSubscriber(...)} — attaches to the
 *      shared encoder or creates it on first use;
 *   3. socket closure / session cleanup releases both references in reverse
 *      order, so the last session streaming from a display (a) detaches its
 *      subscriber and (b) drops its VD refcount.
 *
 * <p>One session may hold multiple {@code SessionVideoController} instances
 * (one per displayId). They do not share state.
 */
public final class SessionVideoController implements VideoController {

    private final int sessionId;
    /** The display this controller instance streams from. */
    private final int displayId;
    private final TcpVideoBinding videoBinding;
    private volatile Options options;
    private volatile String[] baseArgs;
    private final DisplaySurfaceBroker surfaceBroker;
    private final FrameBroadcasterRegistry broadcasterRegistry;
    private final VirtualDisplayRegistry virtualDisplayRegistry;
    private final BooleanSupplier isSessionExited;

    private Controller controller;
    private VideoSubscriber currentSubscriber;

    private final AtomicBoolean videoStarted = new AtomicBoolean(false);
    private final AtomicBoolean vdAcquired = new AtomicBoolean(false);
    private volatile boolean acquiredVdIsVirtual; // false for displayId==0

    public interface TcpVideoBinding {
        FileDescriptor getVideoFd();
        boolean hasVideo();
        void bindVideoSocket(Socket socket) throws IOException;
    }

    public SessionVideoController(int sessionId, int displayId, TcpVideoBinding videoBinding,
                                  Options options, String[] baseArgs, DisplaySurfaceBroker surfaceBroker,
                                  FrameBroadcasterRegistry broadcasterRegistry,
                                  VirtualDisplayRegistry virtualDisplayRegistry,
                                  BooleanSupplier isSessionExited) {
        this.sessionId = sessionId;
        this.displayId = displayId;
        this.videoBinding = videoBinding;
        this.options = options;
        this.baseArgs = baseArgs;
        this.surfaceBroker = surfaceBroker;
        this.broadcasterRegistry = broadcasterRegistry;
        this.virtualDisplayRegistry = virtualDisplayRegistry;
        this.isSessionExited = isSessionExited;
    }

    /** @deprecated retained for tests / older callers; use the displayId-aware ctor. */
    @Deprecated
    public SessionVideoController(int sessionId, TcpVideoBinding videoBinding, Options options,
                                  String[] baseArgs, DisplaySurfaceBroker surfaceBroker,
                                  FrameBroadcasterRegistry broadcasterRegistry,
                                  BooleanSupplier isSessionExited) {
        this(sessionId, 0, videoBinding, options, baseArgs, surfaceBroker, broadcasterRegistry,
                null, isSessionExited);
    }

    public void setController(Controller controller) {
        this.controller = controller;
    }

    public synchronized void setSessionOptions(Options options, String[] baseArgs) {
        if (videoStarted.get()) {
            throw new IllegalStateException(
                    "Session[" + sessionId + "]: cannot reconfigure options — video already started");
        }
        this.options = options;
        this.baseArgs = baseArgs;
        Ln.i("Session[" + sessionId + "]: session options updated via CONFIGURE_SESSION"
                + " (displayId=" + displayId + ")");
    }

    /**
     * Explicit startVideoStream(displayId). Not driven by a daemon command any
     * longer, but still callable by internal code; the {@code displayId}
     * argument must match this controller's displayId or an {@link
     * IllegalArgumentException} is thrown.
     */
    @Override
    public boolean startVideoStream(int displayId) {
        if (displayId != this.displayId) {
            throw new IllegalArgumentException(
                    "Session[" + sessionId + "]: startVideoStream displayId=" + displayId
                            + " does not match controller displayId=" + this.displayId);
        }
        return startVideoStreamInternal();
    }

    private synchronized boolean startVideoStreamInternal() {
        if (isSessionExited.getAsBoolean()) {
            return false;
        }
        if (videoStarted.get()) {
            Ln.w("Session[" + sessionId + "]: video already started for displayId=" + displayId);
            return true;
        }
        if (!videoBinding.hasVideo() || videoBinding.getVideoFd() == null) {
            Ln.w("Session[" + sessionId + "]: startVideoStreamInternal failed: no video socket available"
                    + " (displayId=" + displayId + ")");
            return false;
        }

        // Step 4: migrate VD acquire here from the removed
        // TYPE_START_VIDEO_STREAM handler. This is the correct place — every
        // video stream, whether started by bind() or by legacy API, must
        // keep its display alive for the duration of the subscription.
        if (virtualDisplayRegistry != null && displayId != 0) {
            if (virtualDisplayRegistry.hasDisplay(displayId)) {
                if (vdAcquired.compareAndSet(false, true)) {
                    virtualDisplayRegistry.acquire(displayId, sessionId);
                    acquiredVdIsVirtual = true;
                }
            }
        }

        Ln.i("Session[" + sessionId + "]: launching video stream for displayId=" + displayId);
        try {
            currentSubscriber = broadcasterRegistry.acquireSubscriber(
                    displayId, sessionId, videoBinding.getVideoFd(),
                    options, baseArgs, controller);
            videoStarted.set(true);
            Ln.i("Session[" + sessionId + "]: video stream started for displayId=" + displayId);
            return true;
        } catch (Exception e) {
            Ln.e("Session[" + sessionId + "]: failed to start video stream displayId=" + displayId, e);
            videoStarted.set(false);
            stopVideoInternal();
            return false;
        }
    }

    @Override
    public synchronized boolean stopVideoStream() {
        if (!videoStarted.get()) {
            Ln.w("Session[" + sessionId + "]: video not started (displayId=" + displayId + ")");
            return false;
        }
        stopVideoInternal();
        return true;
    }

    /**
     * Single teardown entry point. Always safe to call, even if nothing is
     * running — used by bind rollback, session cleanup, and explicit stop.
     */
    public synchronized void stopVideoInternal() {
        if (currentSubscriber != null) {
            broadcasterRegistry.releaseSubscriber(displayId, sessionId, currentSubscriber);
            currentSubscriber = null;
        }
        videoStarted.set(false);

        // Release the VD reference we took on start. Releasing here is a
        // best-effort per-stream cleanup; the session also calls
        // VirtualDisplayRegistry.releaseSession(sessionId) during cleanup()
        // which drops all per-session refs atomically, so a transient
        // double-release of the same ref by this path is impossible (the
        // registry uses sessionId keying — releasing a ref we already
        // released is a no-op).
        if (vdAcquired.compareAndSet(true, false) && virtualDisplayRegistry != null && acquiredVdIsVirtual) {
            virtualDisplayRegistry.release(displayId, sessionId);
        }
        Ln.i("Session[" + sessionId + "]: video stream stopped (displayId=" + displayId + ")");
    }

    @Override
    public boolean isVideoStarted() {
        return videoStarted.get();
    }

    /**
     * Called from the accept loop when a (ROLE_VIDEO, displayId) socket
     * arrives for this session. Binds the socket, then immediately starts
     * the stream (VD acquire + subscriber acquire). Replacement for the old
     * TYPE_START_VIDEO_STREAM daemon command.
     */
    public void bindVideoSocket(Socket socket) {
        synchronized (this) {
            try {
                videoBinding.bindVideoSocket(socket);
                Ln.i("Session[" + sessionId + "]: video socket bound for displayId=" + displayId
                        + ", auto-starting stream");
                boolean ok = startVideoStreamInternal();
                if (!ok) {
                    // Roll back the binding if start failed — nothing to
                    // release because startVideoStreamInternal handles the
                    // subscriber+VD rollback internally.
                    Ln.w("Session[" + sessionId + "]: auto-start failed for displayId=" + displayId);
                }
            } catch (IOException e) {
                Ln.e("Session[" + sessionId + "]: failed to bind video socket displayId=" + displayId, e);
            }
        }
    }
}
