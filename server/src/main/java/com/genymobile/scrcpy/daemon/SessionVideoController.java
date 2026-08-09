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

    private int requestedDisplayId = -1;
    private volatile boolean videoStreamRequested = false;

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

        synchronized (this) {
            Ln.i("Session[" + sessionId + "]: startVideoStream requested for displayId=" + displayId);
            requestedDisplayId = displayId;
            videoStreamRequested = true;

            // If the video socket was bound early (e.g. on reconnection), start immediately.
            if (videoBinding.hasVideo() && videoBinding.getVideoFd() != null) {
                return startVideoStreamInternal();
            }
            return true;
        }
    }

    private synchronized boolean startVideoStreamInternal() {
        if (isSessionExited.getAsBoolean()) {
            return false;
        }
        if (!videoStreamRequested) {
            return false;
        }
        if (videoStarted.get()) {
            Ln.w("Session[" + sessionId + "]: video already started for display " + requestedDisplayId);
            return true;
        }
        if (!videoBinding.hasVideo() || videoBinding.getVideoFd() == null) {
            Ln.w("Session[" + sessionId + "]: startVideoStreamInternal failed: no video socket available");
            return false;
        }

        Ln.i("Session[" + sessionId + "]: launching video stream for displayId=" + requestedDisplayId);

        try {
            DaemonVideoPipeline.Built built = DaemonVideoPipeline.build(
                    baseArgs, requestedDisplayId, options, videoBinding.getVideoFd(), surfaceBroker, controller);
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
            }, "video-" + sessionId + "-" + requestedDisplayId);
            videoThread.setDaemon(true);
            videoThread.start();

            Ln.i("Session[" + sessionId + "]: video stream started for display " + requestedDisplayId);
            return true;
        } catch (Exception e) {
            Ln.e("Session[" + sessionId + "]: failed to start video stream", e);
            videoStarted.set(false);
            stopVideoInternal();
            return false;
        }
    }

    @Override
    public synchronized boolean stopVideoStream() {
        if (!videoStarted.get() && !videoStreamRequested) {
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
        videoStreamRequested = false;
        videoStarted.set(false);
        if (surfaceEncoder != null) {
            surfaceEncoder.stop();
        }
        // Join the video thread to ensure the old encoder's termination callback
        // has fully completed before allowing a new video stream to start.
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
        synchronized (this) {
            try {
                videoBinding.bindVideoSocket(socket);
                if (videoStreamRequested) {
                    Ln.i("Session[" + sessionId + "]: video socket bound, triggering startVideoStreamInternal");
                    startVideoStreamInternal();
                } else {
                    Ln.i("Session[" + sessionId + "]: video socket bound but video stream not requested yet");
                }
            } catch (IOException e) {
                Ln.e("Session[" + sessionId + "]: failed to bind video socket", e);
            }
        }
    }
}
