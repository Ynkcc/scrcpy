package com.genymobile.scrcpy;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

public final class DaemonManager {

    private static DaemonManager instance;

    private final Map<Integer, VirtualDisplay> activeDisplays = new HashMap<>();
    private final Object activeDisplaysLock = new Object();

    private volatile int targetDisplayId = 0;

    private DaemonManager() {
    }

    public static synchronized DaemonManager getInstance() {
        if (instance == null) {
            instance = new DaemonManager();
        }
        return instance;
    }

    public int getTargetDisplayId() {
        return targetDisplayId;
    }

    public void setTargetDisplayId(int displayId) {
        this.targetDisplayId = displayId;
        Ln.i("DaemonManager: targetDisplayId set to " + displayId);
    }

    public int createVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        VirtualDisplay vd = null;
        try {
            Ln.i("DaemonManager: createVirtualDisplay name=" + name + ", width=" + width + ", height=" + height + ", dpi=" + dpi + ", flags=0x" + Integer.toHexString(flags));
            vd = ServiceManager.getDisplayManager()
                    .createNewVirtualDisplay(name, width, height, dpi, null, flags);
            int displayId = vd.getDisplay().getDisplayId();
            synchronized (activeDisplaysLock) {
                activeDisplays.put(displayId, vd);
            }
            Ln.i("DaemonManager: created virtual display id=" + displayId + " (" + width + "x" + height + "/" + dpi + ")");
            return displayId;
        } catch (Exception e) {
            Ln.e("DaemonManager: failed to create virtual display", e);
            if (vd != null) {
                try {
                    vd.release();
                } catch (Exception releaseEx) {
                    Ln.w("DaemonManager: failed to release leaked virtual display", releaseEx);
                }
            }
            return -1;
        }
    }

    public boolean releaseVirtualDisplay(int displayId) {
        VirtualDisplay vd;
        synchronized (activeDisplaysLock) {
            vd = activeDisplays.remove(displayId);
        }
        if (vd != null) {
            try {
                vd.release();
                Ln.i("DaemonManager: released virtual display id=" + displayId);
                return true;
            } catch (Exception e) {
                Ln.e("DaemonManager: failed to release virtual display id=" + displayId, e);
                return false;
            }
        }
        Ln.w("DaemonManager: display id=" + displayId + " not found for release");
        return false;
    }

    public boolean resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        VirtualDisplay vd;
        synchronized (activeDisplaysLock) {
            vd = activeDisplays.get(displayId);
        }
        if (vd != null) {
            try {
                vd.resize(width, height, dpi);
                Ln.i("DaemonManager: resized virtual display id=" + displayId + " to " + width + "x" + height + "/" + dpi);
                return true;
            } catch (Exception e) {
                Ln.e("DaemonManager: failed to resize virtual display id=" + displayId, e);
                return false;
            }
        }
        Ln.w("DaemonManager: display id=" + displayId + " not found for resize");
        return false;
    }

    public int startActivity(String packageName, int displayId) {
        try {
            Ln.i("DaemonManager: startActivity for package=" + packageName + " on display " + displayId);
            PackageManager pm = FakeContext.get().getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName);
            }
            if (launchIntent == null) {
                Ln.w("DaemonManager: Cannot create launch intent for app " + packageName);
                return -1;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Bundle options = null;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
                ActivityOptions launchOptions = ActivityOptions.makeBasic();
                launchOptions.setLaunchDisplayId(displayId);
                options = launchOptions.toBundle();
            }

            int result = ServiceManager.getActivityManager().startActivity(launchIntent, options);
            Ln.i("DaemonManager: startActivity result=" + result);
            return result > 0 ? 0 : -1;
        } catch (Exception e) {
            Ln.e("DaemonManager: failed to start activity", e);
            return -1;
        }
    }

    public int[] getActiveDisplayIds() {
        synchronized (activeDisplaysLock) {
            int[] ids = new int[activeDisplays.size()];
            int i = 0;
            for (int id : activeDisplays.keySet()) {
                ids[i++] = id;
            }
            return ids;
        }
    }

    public void releaseAll() {
        synchronized (activeDisplaysLock) {
            for (VirtualDisplay vd : activeDisplays.values()) {
                try {
                    vd.release();
                } catch (Exception e) {
                    Ln.e("DaemonManager: failed to release display during cleanup", e);
                }
            }
            activeDisplays.clear();
        }
        Ln.i("DaemonManager: all virtual displays released");
    }
}
