package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.DeviceMessageSender;
import com.genymobile.scrcpy.daemon.control.DaemonCommandHandler;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.net.TcpDesktopConnection;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientSession implements VideoController, Runnable {

    private final int sessionId;
    private final TcpDesktopConnection connection;
    private final Options options;
    private final String[] baseArgs;
    private final VirtualDisplayRegistry registry;
    private final DisplaySurfaceBroker surfaceBroker;
    private final DaemonExitCoordinator exitCoordinator;
    private final DaemonServer server;

    private Controller controller;
    private DaemonCommandHandler daemonCommandHandler;
    private SurfaceEncoder surfaceEncoder;
    private SurfaceCapture surfaceCapture;
    private Streamer videoStreamer;
    private Options sessionOptions;

    private final AtomicBoolean videoStarted = new AtomicBoolean(false);
    private final AtomicBoolean exited = new AtomicBoolean(false);
    private Thread videoThread;

    // Signaled once the first video socket is bound to the connection, so that
    // startVideoStream can wait for it WITHOUT holding the session lock.
    private final CountDownLatch videoSocketLatch = new CountDownLatch(1);

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
            sessionOptions = options;
            controller = new Controller(connection.getControlChannel(), null, sessionOptions);
            Ln.i("Session[" + sessionId + "]: Controller created, displayId=" + sessionOptions.getDisplayId());

            daemonCommandHandler = new DaemonCommandHandler(controller, registry, exitCoordinator, this);
            controller.setControlMessageExtension(daemonCommandHandler);
            Ln.i("Session[" + sessionId + "]: DaemonCommandHandler extension injected");

            Thread ctrlThread = new Thread(() -> {
                try {
                    Ln.i("Session[" + sessionId + "]: calling controller.start()");
                    controller.start(fatalError -> {
                        Ln.i("Session[" + sessionId + "]: controller terminated, fatal=" + fatalError);
                        controller.stop();
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
            Ln.i("Session[" + sessionId + "]: closed");
            if (exitCoordinator.isExitRequested()) {
                Ln.i("Session[" + sessionId + "]: Exit coordinator requested shutdown, exiting.");
            }
        }
    }

    @Override
    public boolean startVideoStream(int displayId) {
        if (exited.get()) {
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
            if (exited.get()) {
                Ln.w("Session[" + sessionId + "]: session exited while waiting for video socket");
                return false;
            }
            if (videoStarted.get()) {
                Ln.w("Session[" + sessionId + "]: video already started for display " + displayId);
                return false;
            }

            Ln.i("Session[" + sessionId + "]: start video stream for displayId=" + displayId);

            try {
                String[] modifiedArgs = DaemonArgs.changeDisplayId(baseArgs, displayId);
                Options captureOptions = Options.parse(modifiedArgs);

                videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(),
                        options.getSendStreamMeta(), options.getSendFrameMeta());

                ScreenCapture screen = new ScreenCapture(
                        controller != null ? controller : (id, pm) -> {},
                        captureOptions);
                screen.setExternalDisplayProvider(surfaceBroker);
                surfaceCapture = screen;
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
    }

    private void ensureVideoFdReady() throws IOException {
        if (connection.hasVideo() && connection.getVideoFd() != null) {
            return;
        }

        Ln.i("Session[" + sessionId + "]: waiting for video socket...");
        try {
            if (!videoSocketLatch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Video socket not connected within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for video socket");
        }

        if (!connection.hasVideo() || connection.getVideoFd() == null) {
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

    // Single video-teardown entry point. Synchronized so that concurrent
    // callers (stopVideoStream, cleanup, failed startVideoStream) serialize
    // instead of double-releasing surfaceEncoder/surfaceCapture/videoThread.
    private synchronized void stopVideoInternal() {
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

    public void onVideoSocket(Socket socket) {
        try {
            connection.bindVideoSocket(socket);
            videoSocketLatch.countDown();
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
