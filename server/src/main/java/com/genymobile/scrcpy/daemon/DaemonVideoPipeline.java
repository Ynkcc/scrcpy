package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.video.FrameSink;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VirtualDisplayListener;

/**
 * core/video factory: builds the shared capture+encode pipeline for a
 * {@link com.genymobile.scrcpy.daemon.video.FrameBroadcaster}.
 *
 * <p>This is the single daemon class that concentrates imports of upstream
 * {@code video/ScreenCapture} and {@code video/SurfaceEncoder}, keeping the
 * upstream-coupling knowledge in one place. The owning
 * {@code FrameBroadcaster} only holds the built references and drives their
 * lifecycle.
 *
 * <p>Unlike the former per-session {@code build()}, {@link #buildBroadcaster}
 * takes a {@link FrameSink} (the broadcaster itself) instead of a socket FD:
 * the shared encoder writes to the broadcaster, which fans out to N
 * subscriber sockets. No {@code Streamer} is constructed here — each
 * subscriber owns its own {@code Streamer} wrapping its own socket FD.
 */
public final class DaemonVideoPipeline {

    /** Immutable holder for the constructed pipeline components. */
    public static final class Built {
        private final SurfaceCapture surfaceCapture;
        private final SurfaceEncoder surfaceEncoder;

        Built(SurfaceCapture surfaceCapture, SurfaceEncoder surfaceEncoder) {
            this.surfaceCapture = surfaceCapture;
            this.surfaceEncoder = surfaceEncoder;
        }

        public SurfaceCapture getSurfaceCapture() {
            return surfaceCapture;
        }

        public SurfaceEncoder getSurfaceEncoder() {
            return surfaceEncoder;
        }
    }

    private DaemonVideoPipeline() {
    }

    /**
     * Build the shared capture+encode pipeline for a broadcaster.
     *
     * <p>Mirrors the construction previously inlined in the old per-session
     * {@code build()}: rewrite {@code display_id} in the base args, re-parse
     * options, then wire {@link ScreenCapture} (with the daemon
     * {@link DisplaySurfaceBroker} injected as {@code ExternalDisplayProvider})
     * → {@link SurfaceEncoder} writing to {@code sink} (the broadcaster).
     *
     * <p>Deliberately does <b>not</b> call {@code controller.setSurfaceCapture()}:
     * the capture is shared across all subscribers and must not be mutated by
     * any one session's controller. {@code TYPE_RESIZE_VIRTUAL_DISPLAY} now
     * routes through {@code FrameBroadcasterRegistry} instead of the
     * per-session controller's surfaceCapture.
     *
     * @param displayId     target display id for this stream
     * @param baseArgs      the scrubbed scrcpy args (display_id will be rewritten)
     * @param options       the base session options (codec / stream-meta flags come from here)
     * @param surfaceBroker daemon-managed external display provider
     * @param controller    the first subscriber's controller (used as
     *                      {@code VirtualDisplayListener}); may be {@code null}
     *                      in which case a no-op listener is used
     * @param sink          the broadcaster (FrameSink) that receives encoded packets
     * @return a {@link Built} holder referencing the freshly created pipeline
     * @throws Exception if option parsing or pipeline construction fails (caller handles)
     */
    public static Built buildBroadcaster(int displayId, String[] baseArgs, Options options,
                                         DisplaySurfaceBroker surfaceBroker,
                                         Controller controller, FrameSink sink) throws Exception {
        String[] modifiedArgs = DaemonArgs.changeDisplayId(baseArgs, displayId);
        Options captureOptions = Options.parse(modifiedArgs);

        // ScreenCapture needs a VirtualDisplayListener; Controller implements it. When the
        // controller is not yet wired (control socket not bound), fall back to a no-op listener
        // so the capture can still be constructed.
        VirtualDisplayListener listener = controller != null ? controller
                : (virtualDisplayId, positionMapper) -> {
                };
        ScreenCapture screen = new ScreenCapture(listener, captureOptions);
        screen.setExternalDisplayProvider(surfaceBroker);
        SurfaceCapture surfaceCapture = screen;

        SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, sink, captureOptions);

        return new Built(surfaceCapture, surfaceEncoder);
    }
}
