package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VirtualDisplayListener;

import java.io.FileDescriptor;

/**
 * core/video factory: builds the per-stream capture+encode pipeline from upstream
 * {@code video/} components.
 *
 * <p>This is the single daemon class that concentrates imports of upstream
 * {@code video/ScreenCapture}, {@code video/SurfaceEncoder} and {@code device/Streamer},
 * keeping the upstream-coupling knowledge in one place (mirrors the app-side
 * {@code core/video} layer of the refactoring plan). The owning
 * {@link SessionVideoController} only holds the built references and drives their
 * lifecycle.
 */
public final class DaemonVideoPipeline {

    /** Immutable holder for the constructed pipeline components. */
    public static final class Built {
        private final SurfaceCapture surfaceCapture;
        private final SurfaceEncoder surfaceEncoder;
        private final Streamer streamer;

        Built(SurfaceCapture surfaceCapture, SurfaceEncoder surfaceEncoder, Streamer streamer) {
            this.surfaceCapture = surfaceCapture;
            this.surfaceEncoder = surfaceEncoder;
            this.streamer = streamer;
        }

        public SurfaceCapture getSurfaceCapture() {
            return surfaceCapture;
        }

        public SurfaceEncoder getSurfaceEncoder() {
            return surfaceEncoder;
        }

        public Streamer getStreamer() {
            return streamer;
        }
    }

    private DaemonVideoPipeline() {
    }

    /**
     * Build the capture+encode pipeline for the requested {@code displayId}.
     *
     * <p>Mirrors the construction previously inlined in {@code ClientSession.startVideoStream}:
     * rewrite {@code display_id} in the base args, re-parse options, then wire
     * {@link Streamer} → {@link ScreenCapture} (with the daemon
     * {@link DisplaySurfaceBroker} injected as {@code ExternalDisplayProvider}) →
     * {@link SurfaceEncoder}, and push the capture back onto the {@link Controller}.
     *
     * @param baseArgs       the scrubbed scrcpy args (daemon params already stripped)
     * @param displayId      target display id for this stream
     * @param options        the base session options (codec / stream-meta flags come from here)
     * @param videoFd        file descriptor of the bound video socket
     * @param surfaceBroker  daemon-managed external display provider
     * @param controller     the session controller (used as VirtualDisplayListener and for
     *                       setSurfaceCapture); may be {@code null} before the controller is
     *                       wired, in which case a no-op listener is used
     * @return a {@link Built} holder referencing the freshly created pipeline
     * @throws Exception if option parsing or pipeline construction fails (caller handles)
     */
    public static Built build(String[] baseArgs, int displayId, Options options,
                              FileDescriptor videoFd, DisplaySurfaceBroker surfaceBroker,
                              Controller controller) throws Exception {
        String[] modifiedArgs = DaemonArgs.changeDisplayId(baseArgs, displayId);
        Options captureOptions = Options.parse(modifiedArgs);

        Streamer videoStreamer = new Streamer(videoFd, options.getVideoCodec(),
                options.getSendStreamMeta(), options.getSendFrameMeta());

        // ScreenCapture needs a VirtualDisplayListener; Controller implements it. When the
        // controller is not yet wired, fall back to a no-op listener so the capture can still
        // be constructed (matches the previous inline `controller != null ? controller : ...`).
        VirtualDisplayListener listener = controller != null ? controller : (virtualDisplayId, positionMapper) -> {
        };
        ScreenCapture screen = new ScreenCapture(listener, captureOptions);
        screen.setExternalDisplayProvider(surfaceBroker);
        SurfaceCapture surfaceCapture = screen;

        SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoStreamer, captureOptions);

        if (controller != null) {
            controller.setSurfaceCapture(surfaceCapture);
        }

        return new Built(surfaceCapture, surfaceEncoder, videoStreamer);
    }
}
