package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Owns the per-session video capture+encode lifecycle, extracted from the former
 * {@code ClientSession} god-object.
 *
 * <p>Responsibilities (previously inlined in {@code ClientSession}):
 * <ul>
 *   <li>wait for / bind the video socket delivered by the accept loop;</li>
 *   <li>build the capture pipeline via {@link DaemonVideoPipeline};</li>
 *   <li>drive the {@code videoThread} (encoder start/join) and its termination callback;</li>
 *   <li>serialized teardown ({@link #stopVideoInternal}) preserving the original concurrency
 *       hardening: {@code videoSocketReady} flag + {@code videoSocketLock} awaited outside
 *       the session lock, and {@code videoThread.join(2000)} to prevent the start→stop→start
 *       callback race (see project_memory lessons).</li>
 * </ul>
 *
 * <p>The session-level exit flag is queried via the injected {@link BooleanSupplier} so this
 * class does not own session lifecycle state.
 */
public final class SessionVideoController implements VideoController {

    private final int sessionId;
    private final TcpVideoBinding videoBinding;
    private final Options options;
    private final String[] baseArgs;
    private final DisplaySurfaceBroker surfaceBroker;
    private final BooleanSupplier isSessionExited;

    private Controller controller;
    private SurfaceEncoder surfaceEncoder;
    private SurfaceCapture surfaceCapture;
    private Streamer videoStreamer;

    private final AtomicBoolean videoStarted = new AtomicBoolean(false);
    private Thread videoThread;

    // Replaces the previous CountDownLatch(1). Once a video socket is bound
    // (initial or reconnection), the flag stays true for the lifetime of the
    // session. ensureVideoFdReady uses this flag + wait/notify to block until
    // the first video socket arrives. Subsequent startVideoStream calls use
    // the fast path (the socket is already bound). The client-side 50ms delay
    // after reconnectVideoSocket() ensures the accept loop has bound the new
    // socket before 209 arrives, so the fast path always sees the latest FD.
    private final Object videoSocketLock = new Object();
    private volatile boolean videoSocketReady = false;

    /**
     * Abstracts the video-socket-bearing connection so this class does not depend on the full
     * {@code TcpDesktopConnection}. Exposes only the FD and the bind hook.
     */
    public interface TcpVideoBinding {
        FileDescriptor getVideoFd();

        boolean hasVideo();

        void bindVideoSocket(Socket socket) throws IOException;
    }

    public SessionVideoController(int sessionId, TcpVideoBinding videoBinding, Options options,
                                  String[] baseArgs, DisplaySurfaceBroker surfaceBroker,
                                  BooleanSupplier isSessionExited) {
        this.sessionId = sessionId;
        this.videoBinding = videoBinding;
        this.options = options;
        this.baseArgs = baseArgs;
        this.surfaceBroker = surfaceBroker;
        this.isSessionExited = isSessionExited;

        // If the video socket was bound early (before this controller was created,
        // via ClientSession.onVideoSocket → connection.bindVideoSocket), mark it as
        // ready so ensureVideoFdReady's fast path works without waiting.
        if (videoBinding.hasVideo() && videoBinding.getVideoFd() != null) {
            videoSocketReady = true;
        }
    }

    /** Late-wire the controller once the session has constructed it. */
    public void setController(Controller controller) {
        this.controller = controller;
    }

    @Override
    public boolean startVideoStream(int displayId) {
        if (isSessionExited.getAsBoolean()) {
            Ln.w("Session[" + sessionId + "]: session exiting, cannot start video stream");
            return false;
        }

        // Wait for the video socket OUTSIDE the session lock. The previous
        // implementation polled connection.hasVideo() while holding `this`,
        // which blocked a concurrent stop/shutdown for up to 10s.
        try {
            ensureVideoFdReady();
        } catch (IOException e) {
            Ln.e("Session[" + sessionId + "]: video socket not ready for displayId=" + displayId, e);
            return false;
        }

        synchronized (this) {
            if (isSessionExited.getAsBoolean()) {
                Ln.w("Session[" + sessionId + "]: session exited while waiting for video socket");
                return false;
            }
            if (videoStarted.get()) {
                Ln.w("Session[" + sessionId + "]: video already started for display " + displayId);
                return false;
            }

            Ln.i("Session[" + sessionId + "]: start video stream for displayId=" + displayId);

            try {
                DaemonVideoPipeline.Built built = DaemonVideoPipeline.build(
                        baseArgs, displayId, options, videoBinding.getVideoFd(), surfaceBroker, controller);
                surfaceCapture = built.getSurfaceCapture();
                surfaceEncoder = built.getSurfaceEncoder();
                videoStreamer = built.getStreamer();

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
    }

    private void ensureVideoFdReady() throws IOException {
        // Fast path: video socket is ready and has a valid FD
        if (videoSocketReady && videoBinding.hasVideo() && videoBinding.getVideoFd() != null) {
            return;
        }

        Ln.i("Session[" + sessionId + "]: waiting for video socket...");
        synchronized (videoSocketLock) {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!videoSocketReady || !videoBinding.hasVideo() || videoBinding.getVideoFd() == null) {
                if (isSessionExited.getAsBoolean()) {
                    throw new IOException("Session exiting while waiting for video socket");
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Video socket not connected within timeout");
                }
                try {
                    videoSocketLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for video socket");
                }
            }
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

    // Single video-teardown entry point. Synchronized so that concurrent
    // callers (stopVideoStream, cleanup, failed startVideoStream) serialize
    // instead of double-releasing surfaceEncoder/surfaceCapture/videoThread.
    public synchronized void stopVideoInternal() {
        videoStarted.set(false);
        if (surfaceEncoder != null) {
            surfaceEncoder.stop();
        }
        // Join the video thread to ensure the old encoder's termination callback
        // has fully completed before allowing a new video stream to start.
        // Without this, a start→stop→start sequence can race: the old callback
        // resets videoStarted=false after the new stream already set it true,
        // causing the subsequent stop to wrongly report "not started".
        if (videoThread != null) {
            try {
                videoThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            videoThread = null;
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

    /** Called from the accept loop when a video socket arrives for this session. */
    public void bindVideoSocket(Socket socket) {
        try {
            videoBinding.bindVideoSocket(socket);
            synchronized (videoSocketLock) {
                videoSocketReady = true;
                videoSocketLock.notifyAll();
            }
        } catch (IOException e) {
            Ln.e("Session[" + sessionId + "]: failed to bind video socket", e);
        }
    }
}
