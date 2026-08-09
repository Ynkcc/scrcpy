package com.genymobile.scrcpy;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.compat.DisplayCompat;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class DaemonManager {

    private static DaemonManager instance;

    private final Map<Integer, VirtualDisplaySession> activeSessions = new HashMap<>();
    private final Object activeDisplaysLock = new Object();

    private volatile int targetDisplayId = 0;
    private volatile boolean exitDaemonRequested = false;

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

    public boolean isExitDaemonRequested() {
        return exitDaemonRequested;
    }

    public void requestExitDaemon() {
        this.exitDaemonRequested = true;
    }

    public void resetExitDaemon() {
        this.exitDaemonRequested = false;
    }

    public VirtualDisplay getVirtualDisplay(int displayId) {
        synchronized (activeDisplaysLock) {
            VirtualDisplaySession session = activeSessions.get(displayId);
            return session != null ? session.getVirtualDisplay() : null;
        }
    }

    public boolean hasDisplay(int displayId) {
        synchronized (activeDisplaysLock) {
            return activeSessions.containsKey(displayId);
        }
    }

    public void setDisplaySurface(int displayId, android.view.Surface surface) {
        VirtualDisplaySession session;
        synchronized (activeDisplaysLock) {
            session = activeSessions.get(displayId);
        }
        if (session != null) {
            session.setExternalSurface(surface);
            Ln.i("DaemonManager: setDisplaySurface for displayId=" + displayId + ", surface=" + surface);
        } else {
            Ln.w("DaemonManager: setDisplaySurface failed, displayId=" + displayId + " not found");
        }
    }

    public void restoreFallbackSurface(int displayId) {
        VirtualDisplaySession session;
        synchronized (activeDisplaysLock) {
            session = activeSessions.get(displayId);
        }
        if (session != null) {
            session.restoreFallbackSurface();
        }
    }

    public int createVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        VirtualDisplay vd = null;
        ImageReader imageReader = null;
        HandlerThread readerThread = null;
        try {
            Ln.i("DaemonManager: createVirtualDisplay name=" + name + ", width=" + width + ", height=" + height + ", dpi=" + dpi + ", flags=0x" + Integer.toHexString(flags));

            readerThread = new HandlerThread("VDReader-" + name);
            readerThread.start();
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try {
                    android.media.Image img = reader.acquireLatestImage();
                    if (img != null) {
                        img.close();
                    }
                } catch (Throwable t) {
                    Ln.w("DaemonManager: ImageReader error", t);
                }
            }, new Handler(readerThread.getLooper()));

            vd = ServiceManager.getDisplayManager()
                    .createNewVirtualDisplay(name, width, height, dpi, imageReader.getSurface(), flags);
            int displayId = vd.getDisplay().getDisplayId();

            boolean powered = false;
            try {
                powered = ServiceManager.getDisplayManager().requestDisplayPower(displayId, true);
                Ln.i("DaemonManager: requestDisplayPower(" + displayId + ", true) returned: " + powered);
            } catch (Throwable t) {
                Ln.w("DaemonManager: requestDisplayPower not supported on this device/Android version: " + t.getMessage());
            }

            VirtualDisplaySession session = new VirtualDisplaySession(displayId, name, vd, imageReader, readerThread);
            synchronized (activeDisplaysLock) {
                activeSessions.put(displayId, session);
            }
            Ln.i("DaemonManager: created virtual display id=" + displayId + " (" + width + "x" + height + "/" + dpi + "), powered=" + powered);
            return displayId;
        } catch (Exception e) {
            Ln.e("DaemonManager: failed to create virtual display", e);
            if (imageReader != null) {
                try {
                    imageReader.close();
                } catch (Exception ignore) {
                }
            }
            if (readerThread != null) {
                readerThread.quitSafely();
            }
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
        VirtualDisplaySession session;
        synchronized (activeDisplaysLock) {
            session = activeSessions.remove(displayId);
        }

        if (session != null) {
            try {
                DisplayCompat.moveTasksToDefaultDisplay(displayId);
                session.close();
                Ln.i("DaemonManager: released virtual display id=" + displayId);
                return true;
            } catch (Exception e) {
                Ln.e("DaemonManager: failed to release virtual display id=" + displayId, e);
                return false;
            }
        }
        Ln.w("DaemonManager: display id=" + displayId + " not found in activeSessions, performing best-effort release of orphan display");
        return DisplayCompat.bestEffortReleaseOrphan(displayId);
    }

    public boolean resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        VirtualDisplaySession session;
        synchronized (activeDisplaysLock) {
            session = activeSessions.get(displayId);
        }
        if (session != null) {
            try {
                session.getVirtualDisplay().resize(width, height, dpi);
                Ln.i("DaemonManager: resized virtual display id=" + displayId + " to " + width + "x" + height + "/" + dpi);

                ImageReader oldReader = session.getImageReader();
                HandlerThread readerThread = session.getReaderThread();
                if (oldReader != null && readerThread != null) {
                    try {
                        oldReader.close();
                    } catch (Exception ignore) {
                    }
                    Handler handler = new Handler(readerThread.getLooper());
                    ImageReader newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
                    newReader.setOnImageAvailableListener(reader -> {
                        try {
                            android.media.Image img = reader.acquireLatestImage();
                            if (img != null) {
                                img.close();
                            }
                        } catch (Throwable t) {
                            Ln.w("DaemonManager: ImageReader error during resize", t);
                        }
                    }, handler);
                    session.setImageReader(newReader);

                    android.view.Surface currentSurface = session.getExternalSurface();
                    session.getVirtualDisplay().setSurface(currentSurface != null ? currentSurface : newReader.getSurface());
                }
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
            return result >= 0 ? 0 : -1;
        } catch (Exception e) {
            Ln.e("DaemonManager: failed to start activity", e);
            return -1;
        }
    }

    public int[] getActiveDisplayIds() {
        synchronized (activeDisplaysLock) {
            int[] ids = new int[activeSessions.size()];
            int i = 0;
            for (int id : activeSessions.keySet()) {
                ids[i++] = id;
            }
            return ids;
        }
    }

    public void releaseAll() {
        int[] ids;
        synchronized (activeDisplaysLock) {
            ids = new int[activeSessions.size()];
            int i = 0;
            for (int id : activeSessions.keySet()) {
                ids[i++] = id;
            }
        }
        for (int id : ids) {
            releaseVirtualDisplay(id);
        }
        Ln.i("DaemonManager: all virtual displays released");
    }
}
