package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.video.ExternalDisplayProvider;
import android.hardware.display.VirtualDisplay;

public final class DisplaySurfaceBroker implements ExternalDisplayProvider {

    private final VirtualDisplayRegistry registry;

    public DisplaySurfaceBroker(VirtualDisplayRegistry registry) {
        this.registry = registry;
    }

    @Override
    public VirtualDisplay get(int displayId) {
        return registry.getVirtualDisplay(displayId);
    }

    @Override
    public void restore(int displayId) {
        VirtualDisplaySession session = registry.getSession(displayId);
        if (session != null) {
            session.restoreFallbackSurface();
        }
    }
}
