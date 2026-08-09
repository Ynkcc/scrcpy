package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.daemon.compat.DisplayCompat;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.util.HashMap;
import java.util.Map;

public final class VirtualDisplayRegistry {

    private final Map<Integer, VirtualDisplaySession> activeSessions = new HashMap<>();
    private final Object activeDisplaysLock = new Object();

    public VirtualDisplayRegistry() {
    }

    public VirtualDisplay getVirtualDisplay(int displayId) {
        synchronized (activeDisplaysLock) {
            VirtualDisplaySession session = activeSessions.get(displayId);
            return session != null ? session.getVirtualDisplay() : null;
        }
    }

    public VirtualDisplaySession getSession(int displayId) {
        synchronized (activeDisplaysLock) {
            return activeSessions.get(displayId);
        }
    }

    public boolean hasDisplay(int displayId) {
        synchronized (activeDisplaysLock) {
            return activeSessions.containsKey(displayId);
        }
    }

    public int createVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        VirtualDisplay vd = null;
        ImageReader imageReader = null;
        HandlerThread readerThread = null;
        try {
            Ln.i("VirtualDisplayRegistry: createVirtualDisplay name=" + name + ", width=" + width + ", height=" + height + ", dpi=" + dpi + ", flags=0x" + Integer.toHexString(flags));

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
                    Ln.w("VirtualDisplayRegistry: ImageReader error", t);
                }
            }, new Handler(readerThread.getLooper()));

            vd = ServiceManager.getDisplayManager()
                    .createNewVirtualDisplay(name, width, height, dpi, imageReader.getSurface(), flags);
            int displayId = vd.getDisplay().getDisplayId();

            boolean powered = false;
            try {
                powered = ServiceManager.getDisplayManager().requestDisplayPower(displayId, true);
                Ln.i("VirtualDisplayRegistry: requestDisplayPower(" + displayId + ", true) returned: " + powered);
            } catch (Throwable t) {
                Ln.w("VirtualDisplayRegistry: requestDisplayPower not supported on this device/Android version: " + t.getMessage());
            }

            VirtualDisplaySession session = new VirtualDisplaySession(displayId, name, vd, imageReader, readerThread);
            synchronized (activeDisplaysLock) {
                activeSessions.put(displayId, session);
            }
            Ln.i("VirtualDisplayRegistry: created virtual display id=" + displayId + " (" + width + "x" + height + "/" + dpi + "), powered=" + powered);
            return displayId;
        } catch (Exception e) {
            Ln.e("VirtualDisplayRegistry: failed to create virtual display", e);
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
                    Ln.w("VirtualDisplayRegistry: failed to release leaked virtual display", releaseEx);
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
                Ln.i("VirtualDisplayRegistry: released virtual display id=" + displayId);
                return true;
            } catch (Exception e) {
                Ln.e("VirtualDisplayRegistry: failed to release virtual display id=" + displayId, e);
                return false;
            }
        }
        Ln.w("VirtualDisplayRegistry: display id=" + displayId + " not found in activeSessions, performing best-effort release of orphan display");
        return DisplayCompat.bestEffortReleaseOrphan(displayId);
    }

    public boolean resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        VirtualDisplaySession session;
        synchronized (activeDisplaysLock) {
            session = activeSessions.get(displayId);
        }
        if (session == null) {
            Ln.w("VirtualDisplayRegistry: display id=" + displayId + " not found for resize");
            return false;
        }

        try {
            // DPI fallback: when the caller does not specify a DPI, query the live value
            // from DisplayInfo so the virtual display keeps consistent density metadata.
            if (dpi <= 0) {
                try {
                    DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
                    if (info != null && info.getDpi() > 0) {
                        dpi = info.getDpi();
                    }
                } catch (Throwable t) {
                    Ln.w("VirtualDisplayRegistry: failed to query DPI from DisplayInfo for displayId=" + displayId, t);
                }
            }

            VirtualDisplay vd = session.getVirtualDisplay();
            Surface externalSurface = session.getExternalSurface();
            ImageReader oldReader = session.getImageReader();
            HandlerThread readerThread = session.getReaderThread();

            if (externalSurface != null) {
                // An external surface (e.g. MediaCodec input surface) is bound and is
                // size-flexible — the ImageReader is not the active render target, so
                // recreating it would be wasted work. Just resize the virtual display.
                vd.resize(width, height, dpi);
                Ln.i("VirtualDisplayRegistry: resized virtual display id=" + displayId + " to " + width + "x" + height
                        + "/" + dpi + " (external surface, reader untouched)");
                return true;
            }

            if (oldReader == null || readerThread == null) {
                // No reader to recreate — best-effort resize only.
                vd.resize(width, height, dpi);
                Ln.i("VirtualDisplayRegistry: resized virtual display id=" + displayId + " to " + width + "x" + height
                        + "/" + dpi + " (no reader)");
                return true;
            }

            // The ImageReader is the active surface. Recreate it atomically to avoid a
            // no-surface window that would drop frames rendered during the swap.
            //
            // Order: create new reader -> bind new surface -> resize -> close old reader.
            // The old surface is released only AFTER the new one is bound, so the display
            // always has a valid render target. (The previous implementation closed the old
            // reader before creating the new one, leaving a frame-drop window.)
            ImageReader newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            try {
                newReader.setOnImageAvailableListener(reader -> {
                    try {
                        android.media.Image img = reader.acquireLatestImage();
                        if (img != null) {
                            img.close();
                        }
                    } catch (Throwable t) {
                        Ln.w("VirtualDisplayRegistry: ImageReader error after resize", t);
                    }
                }, new Handler(readerThread.getLooper()));

                vd.setSurface(newReader.getSurface());
                vd.resize(width, height, dpi);
            } catch (Exception e) {
                try {
                    newReader.close();
                } catch (Exception ignore) {
                }
                throw e;
            }

            session.setImageReader(newReader);
            try {
                oldReader.close();
            } catch (Exception ignore) {
            }

            Ln.i("VirtualDisplayRegistry: resized virtual display id=" + displayId + " to " + width + "x" + height
                    + "/" + dpi + " (reader swapped atomically)");
            return true;
        } catch (Exception e) {
            Ln.e("VirtualDisplayRegistry: failed to resize virtual display id=" + displayId, e);
            return false;
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
        Ln.i("VirtualDisplayRegistry: all virtual displays released");
    }
}
