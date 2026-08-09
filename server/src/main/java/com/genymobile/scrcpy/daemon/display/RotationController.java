package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.view.Surface;

/**
 * Per-display rotation control for daemon mode.
 *
 * <p>Isolates rotation queries/mutations on top of the existing scrcpy wrappers
 * ( {@link com.genymobile.scrcpy.wrappers.WindowManager} and
 * {@link com.genymobile.scrcpy.wrappers.DisplayManager} ) so the daemon command
 * handler stays free of reflection/system-service details. This class does not
 * touch upstream core files.
 *
 * <p>Targets are validated against the {@link VirtualDisplayRegistry}: the main
 * display (id 0) is always allowed; other ids must refer to a daemon-managed
 * virtual display.
 *
 * <p>Note: {@link VirtualDisplayRegistry} already freezes each virtual display
 * to {@link Surface#ROTATION_0} on create/resize and thaws on release. A client
 * freeze/thaw therefore composes with that internal policy; a subsequent resize
 * will re-freeze to ROTATION_0, overriding a client-requested orientation.
 */
public final class RotationController {

    private final VirtualDisplayRegistry registry;

    public RotationController(VirtualDisplayRegistry registry) {
        this.registry = registry;
    }

    private void validate(int displayId) throws Exception {
        if (displayId != 0 && !registry.hasDisplay(displayId)) {
            throw new Exception("Display not found: " + displayId);
        }
    }

    private static void validateRotationValue(int rotation) throws Exception {
        if (rotation < 0 || rotation > 3) {
            throw new Exception("Invalid rotation (expected 0-3): " + rotation);
        }
    }

    /**
     * @return current rotation (0-3) of the display
     */
    public int getRotation(int displayId) throws Exception {
        validate(displayId);
        DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (info == null) {
            throw new Exception("Could not resolve DisplayInfo for displayId=" + displayId);
        }
        return info.getRotation();
    }

    /**
     * Freeze the display at the given rotation (0-3).
     */
    public void freeze(int displayId, int rotation) throws Exception {
        validate(displayId);
        validateRotationValue(rotation);
        ServiceManager.getWindowManager().freezeRotation(displayId, rotation);
        Ln.i("RotationController: froze rotation=" + rotation + " for displayId=" + displayId);
    }

    /**
     * Thaw (un-freeze) the display rotation.
     */
    public void thaw(int displayId) throws Exception {
        validate(displayId);
        ServiceManager.getWindowManager().thawRotation(displayId);
        Ln.i("RotationController: thawed rotation for displayId=" + displayId);
    }

    /**
     * @return 1 if the display rotation is currently frozen, 0 otherwise
     */
    public int isFrozen(int displayId) throws Exception {
        validate(displayId);
        boolean frozen = ServiceManager.getWindowManager().isRotationFrozen(displayId);
        return frozen ? 1 : 0;
    }
}
