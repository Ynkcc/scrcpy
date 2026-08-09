package com.genymobile.scrcpy.video;

import android.hardware.display.VirtualDisplay;

public interface ExternalDisplayProvider {
    VirtualDisplay get(int displayId);
    void restore(int displayId);
}
