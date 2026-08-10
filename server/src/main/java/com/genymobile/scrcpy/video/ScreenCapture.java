package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Orientation;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;
import java.util.Locale;

public class ScreenCapture extends SurfaceCapture {

    private final VirtualDisplayListener vdListener;
    private int displayId;
    private final Rect crop;
    private Orientation.Lock captureOrientationLock;
    private Orientation captureOrientation;
    private final float angle;

    private boolean isDaemonManaged;

    private ExternalDisplayProvider externalDisplayProvider;

    public void setExternalDisplayProvider(ExternalDisplayProvider provider) {
        this.externalDisplayProvider = provider;
    }

    public void setDisplayId(int displayId) {
        if (this.displayId != displayId) {
            Ln.i("ScreenCapture: setDisplayId from " + this.displayId + " to " + displayId);
            this.displayId = displayId;
            displayMonitor.setDisplayId(displayId);
            if (getCaptureControl() != null) {
                getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED);
            }
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    private VideoConstraints videoConstraints;

    private DisplayInfo displayInfo;
    private Size videoSize;

    private final DisplayMonitor displayMonitor = new DisplayMonitor();

    private IBinder display;
    private VirtualDisplay virtualDisplay;

    private AffineMatrix transform;
    private OpenGLRunner glRunner;

    public ScreenCapture(VirtualDisplayListener vdListener, Options options) {
        this.vdListener = vdListener;
        this.displayId = options.getDisplayId();
        assert displayId != Device.DISPLAY_ID_NONE;
        this.crop = options.getCrop();
        this.captureOrientationLock = options.getCaptureOrientationLock();
        this.captureOrientation = options.getCaptureOrientation();
        assert captureOrientationLock != null;
        assert captureOrientation != null;
        this.angle = options.getAngle();
    }

    @Override
    public void init(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        displayMonitor.start(displayId, (props) -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
    }

    @Override
    public void prepare() throws ConfigurationException {
        displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            Ln.e("Display " + displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + displayId);
        }

        if ((displayInfo.getFlags() & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }

        Size displaySize = displayInfo.getSize();
        int displayRotation = displayInfo.getRotation();
        displayMonitor.setSessionDisplayProperties(new DisplayProperties(displaySize, displayRotation));

        if (captureOrientationLock == Orientation.Lock.LockedInitial) {
            // The user requested to lock the video orientation to the current orientation
            captureOrientationLock = Orientation.Lock.LockedValue;
            captureOrientation = Orientation.fromRotation(displayRotation);
        }

        VideoFilter filter = new VideoFilter(displaySize);

        if (crop != null) {
            boolean transposed = (displayRotation % 2) != 0;
            filter.addCrop(crop, transposed);
        }

        boolean locked = captureOrientationLock != Orientation.Lock.Unlocked;
        filter.addOrientation(displayRotation, locked, captureOrientation);
        filter.addAngle(angle);

        transform = filter.getInverseTransform();
        videoSize = filter.getOutputSize().constrain(videoConstraints);
    }

    @Override
    public void start(Surface surface) throws IOException {
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null && !isDaemonManaged) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        isDaemonManaged = false;

        if (externalDisplayProvider != null && bindDaemonVirtualDisplay(surface)) {
            return;
        }

        Size inputSize;
        if (transform != null) {
            // If there is a filter, it must receive the full display content
            inputSize = displayInfo.getSize();
            assert glRunner == null;
            OpenGLFilter glFilter = new AffineOpenGLFilter(transform);
            glRunner = new OpenGLRunner(glFilter);
            surface = glRunner.start(inputSize, videoSize, surface);
        } else {
            // If there is no filter, the display must be rendered at target video size directly
            inputSize = videoSize;
        }

        try {
            virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
            Ln.d("Display: using DisplayManager API for displayId=" + displayId + " arity=5");
        } catch (Exception displayManagerException) {
            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                        .createNewVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayInfo.getDpi(), surface, 0);
                Ln.d("Display: using DisplayManager API for displayId=" + displayId + " arity=6 (fallback)");
            } catch (Exception arity6Exception) {
                if (Build.BRAND.equalsIgnoreCase("oculus") && Build.MODEL.toLowerCase(Locale.ROOT).startsWith("quest")) {
                    // Workaround for buggy createVirtualDisplay on Quest
                    try {
                        virtualDisplay = (VirtualDisplay) VirtualDisplay.class.getDeclaredConstructors()[0].newInstance(null, null, null, surface);
                    } catch (ReflectiveOperationException e) {
                        Ln.e("Could not create VirtualDisplay", e);
                    }
                } else {
                    try {
                        display = createDisplay();

                        Size deviceSize = displayInfo.getSize();
                        int layerStack = displayInfo.getLayerStack();
                        setDisplaySurface(display, surface, deviceSize.toRect(), inputSize.toRect(), layerStack);
                        Ln.d("Display: using SurfaceControl API");
                    } catch (Exception surfaceControlException) {
                        Ln.e("Could not create display using DisplayManager (5-args)", displayManagerException);
                        Ln.e("Could not create display using DisplayManager (6-args)", arity6Exception);
                        Ln.e("Could not create display using SurfaceControl", surfaceControlException);
                        throw new AssertionError("Could not create display");
                    }
                }
            }
        }

        if (vdListener != null) {
            int virtualDisplayId;
            PositionMapper positionMapper;
            if (virtualDisplay == null || displayId == 0) {
                // Surface control or main display: send all events to the original display, relative to the device size
                Size deviceSize = displayInfo.getSize();
                positionMapper = PositionMapper.create(videoSize, transform, deviceSize);
                virtualDisplayId = displayId;
            } else {
                // The positions are relative to the virtual display, not the original display (so use inputSize, not deviceSize!)
                positionMapper = PositionMapper.create(videoSize, transform, inputSize);
                // Always inject touch events into the ORIGINAL display (displayId)
                // rather than the newly-created mirror virtual display, so that input
                // reaches the user-created virtual display instead of the capture mirror.
                virtualDisplayId = displayId;
            }
            vdListener.onNewVirtualDisplay(virtualDisplayId, positionMapper);
        }
    }

    /**
     * Bind the capture surface to a daemon-managed virtual display registered for the current
     * {@code displayId}, if any. Returns {@code true} when a daemon virtual display handled the
     * surface (in which case {@code start()} returns immediately); {@code false} to fall through
     * to the upstream DisplayManager/SurfaceControl path.
     *
     * <p>This isolates the daemon projection branch so the upstream {@code start()} body stays
     * intact and merges cleanly.
     */
    private boolean bindDaemonVirtualDisplay(Surface surface) throws IOException {
        VirtualDisplay daemonVD = externalDisplayProvider.get(displayId);
        if (daemonVD == null) {
            return false;
        }
        Ln.i("ScreenCapture: using daemon-managed virtual display directly (displayId=" + displayId + ")");
        try {
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(null);
            }
            daemonVD.setSurface(surface);
            virtualDisplay = daemonVD;
            isDaemonManaged = true;
            try {
                boolean powered = ServiceManager.getDisplayManager().requestDisplayPower(displayId, true);
                Ln.i("ScreenCapture: requestDisplayPower(" + displayId + ", true) = " + powered);
            } catch (Throwable t) {
                Ln.w("ScreenCapture: requestDisplayPower not supported: " + t.getMessage());
            }

            if (vdListener != null) {
                int virtualDisplayId;
                PositionMapper positionMapper;
                // Match the upstream start() logic exactly (see lines 191-201 above):
                //  - virtualDisplay != null (daemonVD is set) AND displayId != 0 → use VD's real id + inputSize
                //  - displayId == 0 → main display path
                if (displayId == 0) {
                    Size deviceSize = displayInfo != null ? displayInfo.getSize()
                            : new Size(daemonVD.getDisplay().getWidth(), daemonVD.getDisplay().getHeight());
                    positionMapper = PositionMapper.create(videoSize, transform, deviceSize);
                    virtualDisplayId = displayId;
                } else {
                    // Positions are relative to the virtual display content area:
                    // use the encoded target size (inputSize) when a filter is applied,
                    // otherwise the virtual display's own logical size (displaySize).
                    Size inputSize;
                    if (transform != null) {
                        inputSize = displayInfo != null ? displayInfo.getSize()
                                : new Size(daemonVD.getDisplay().getWidth(), daemonVD.getDisplay().getHeight());
                    } else {
                        inputSize = videoSize;
                    }
                    positionMapper = PositionMapper.create(videoSize, transform, inputSize);
                    virtualDisplayId = daemonVD.getDisplay().getDisplayId();
                }
                Ln.i("ScreenCapture: bindDaemonVirtualDisplay vdListener id=" + virtualDisplayId + " (requested=" + displayId + ")");
                vdListener.onNewVirtualDisplay(virtualDisplayId, positionMapper);
            }
        } catch (Exception e) {
            Ln.e("ScreenCapture: failed to bind surface to daemon virtual display", e);
            throw new IOException("Failed to bind surface to daemon virtual display", e);
        }
        return true;
    }

    @Override
    public void stop() {
        if (glRunner != null) {
            glRunner.stopAndRelease();
            glRunner = null;
        }
        if (isDaemonManaged && virtualDisplay != null && externalDisplayProvider != null) {
            externalDisplayProvider.restore(displayId);
        }
    }

    @Override
    public void release() {
        displayMonitor.stopAndRelease();

        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null) {
            if (!isDaemonManaged) {
                virtualDisplay.release();
            } else if (externalDisplayProvider != null) {
                externalDisplayProvider.restore(displayId);
            }
            virtualDisplay = null;
            isDaemonManaged = false;
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        return true;
    }

    private static IBinder createDisplay() throws Exception {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        boolean secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11 || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11
                && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}
