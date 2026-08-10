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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VirtualDisplayRegistry {

    private static final int IMAGE_READER_MAX_IMAGES = 5; // larger buffer to reduce frame drops

    private final Map<Integer, VirtualDisplaySession> activeSessions = new HashMap<>();
    private final Object activeDisplaysLock = new Object();

    // Step 3 (VD 所有权): displayId → set of sessionIds currently using this
    // display (creators + streamers). A display is only destroyed when its user
    // set becomes empty, so a second client can keep a virtual display alive
    // after the first client disconnects — the "lossless recovery" guarantee
    // from refs/13 §7. Guarded by activeDisplaysLock.
    private final Map<Integer, Set<Integer>> displayUsers = new HashMap<>();

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
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
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

            // Isolate virtual display rotation: freeze the initial orientation so that
            // (a) the main screen rotation never rotates the virtual display, and
            // (b) rotation requests inside the virtual display never propagate to the main screen or this app.
            // Use the initial natural rotation (ROTATION_0) as the frozen orientation.
            //
            // The WindowManager may not have registered the newly-created display yet
            // when freezeRotation() is invoked here; that call would then silently fail
            // (the exception is caught inside WindowManager.freezeRotation()). Verify
            // with isRotationFrozen() and retry a few times to bridge the registration
            // race. See RotationController / TYPE_IS_ROTATION_FROZEN for the query path.
            freezeRotationWithRetry(displayId, Surface.ROTATION_0);

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
                    Ln.d("VirtualDisplayRegistry: failed to close ImageReader during create failure cleanup: " + ignore.getMessage());
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
            displayUsers.remove(displayId); // also clear users (force-destroy path)
        }

        if (session != null) {
            return actuallyDestroy(displayId, session);
        }
        Ln.w("VirtualDisplayRegistry: display id=" + displayId + " not found in activeSessions, performing best-effort release of orphan display");
        return DisplayCompat.bestEffortReleaseOrphan(displayId);
    }

    /**
     * Actually tear down a virtual display: thaw rotation, migrate tasks back
     * to the default display, close the session (VirtualDisplay + ImageReader).
     * Called outside {@code activeDisplaysLock} — the heavy I/O must not hold
     * the registry lock.
     */
    private boolean actuallyDestroy(int displayId, VirtualDisplaySession session) {
        try {
            // Thaw (un-freeze) rotation before releasing the virtual display
            // so any previously frozen orientation state is released back to the system.
            try {
                ServiceManager.getWindowManager().thawRotation(displayId);
                Ln.i("VirtualDisplayRegistry: thawed rotation for display id=" + displayId);
            } catch (Throwable t) {
                Ln.w("VirtualDisplayRegistry: failed to thaw rotation for displayId=" + displayId, t);
            }
            DisplayCompat.moveTasksToDefaultDisplay(displayId);
            session.close();
            Ln.i("VirtualDisplayRegistry: released virtual display id=" + displayId);
            return true;
        } catch (Exception e) {
            Ln.e("VirtualDisplayRegistry: failed to release virtual display id=" + displayId, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Step 3: per-session reference counting (VD 所有权)
    // ------------------------------------------------------------------

    /**
     * Register a session as a user of the given virtual display. Must be called
     * after {@link #createVirtualDisplay} (for the creator) or before
     * {@code START_VIDEO_STREAM} (for a streamer attaching to an existing VD).
     *
     * <p>Idempotent: acquiring the same {@code (displayId, sessionId)} twice is
     * a no-op (it's a {@link Set}), so CREATE + START_VIDEO_STREAM from the
     * same session only counts once — a single {@link #release} is enough.
     */
    public void acquire(int displayId, int sessionId) {
        synchronized (activeDisplaysLock) {
            displayUsers.computeIfAbsent(displayId, k -> new HashSet<>()).add(sessionId);
        }
    }

    /**
     * Release a session's reference to a virtual display. If this was the last
     * user, the display is actually destroyed (thaw rotation, migrate tasks,
     * {@code VirtualDisplay.release}). Otherwise it survives for remaining
     * users — the "lossless recovery" guarantee: client A can disconnect
     * without destroying a display that client B is still watching.
     *
     * @return true if the display was destroyed, false if it still has users
     */
    public boolean release(int displayId, int sessionId) {
        VirtualDisplaySession session = null;
        boolean shouldDestroy = false;
        synchronized (activeDisplaysLock) {
            Set<Integer> users = displayUsers.get(displayId);
            if (users != null) {
                users.remove(Integer.valueOf(sessionId));
                if (users.isEmpty()) {
                    displayUsers.remove(displayId);
                    shouldDestroy = true;
                } else {
                    Ln.i("VirtualDisplayRegistry: display id=" + displayId + " still has " + users.size()
                            + " user(s) after release by session " + sessionId + " — not destroyed");
                    return false;
                }
            } else {
                // No users tracked for this display — best-effort force destroy.
                shouldDestroy = true;
            }
            if (shouldDestroy) {
                // Atomically claim the session for destruction while still
                // holding the lock, so a concurrent acquire() can't sneak in
                // between "decide to destroy" and "remove from activeSessions".
                session = activeSessions.remove(displayId);
            }
        }
        if (session != null) {
            return actuallyDestroy(displayId, session);
        }
        if (shouldDestroy) {
            Ln.w("VirtualDisplayRegistry: release(" + displayId + ", session=" + sessionId
                    + ") — not in activeSessions, best-effort orphan release");
            return DisplayCompat.bestEffortReleaseOrphan(displayId);
        }
        return false;
    }

    /**
     * Release all virtual display references held by a session. Called from
     * {@code ClientSession.cleanup()} so that a disconnecting client never
     * orphans displays it created or was streaming. Displays that become
     * userless as a result are destroyed; displays with remaining users
     * survive.
     */
    public void releaseSession(int sessionId) {
        List<Integer> toDestroy = new ArrayList<>();
        synchronized (activeDisplaysLock) {
            for (Iterator<Map.Entry<Integer, Set<Integer>>> it = displayUsers.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, Set<Integer>> entry = it.next();
                if (entry.getValue().remove(Integer.valueOf(sessionId)) && entry.getValue().isEmpty()) {
                    toDestroy.add(entry.getKey());
                    it.remove();
                }
            }
        }
        for (int displayId : toDestroy) {
            VirtualDisplaySession session;
            synchronized (activeDisplaysLock) {
                session = activeSessions.remove(displayId);
            }
            if (session != null) {
                actuallyDestroy(displayId, session);
            }
        }
        Ln.i("VirtualDisplayRegistry: released all refs for session " + sessionId
                + " (" + toDestroy.size() + " display(s) destroyed)");
    }

    public boolean resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        // Hold the registry lock for the whole resize so that a concurrent
        // releaseVirtualDisplay cannot remove the session and call close()
        // (which releases the VirtualDisplay / ImageReader) while we are
        // still operating on them. The previous version released the lock
        // right after get(), leaving a window for IllegalStateException /
        // double-close / leaked newReader.
        synchronized (activeDisplaysLock) {
            VirtualDisplaySession session = activeSessions.get(displayId);
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
                    // Re-freeze rotation after resize to ensure orientation stays isolated.
                    try {
                        ServiceManager.getWindowManager().freezeRotation(displayId, Surface.ROTATION_0);
                    } catch (Throwable ignore) {
                        Ln.w("VirtualDisplayRegistry: freezeRotation failed (best-effort) for displayId=" + displayId, ignore);
                    }
                    return true;
                }

                if (oldReader == null || readerThread == null) {
                    // No reader to recreate — best-effort resize only.
                    vd.resize(width, height, dpi);
                    Ln.i("VirtualDisplayRegistry: resized virtual display id=" + displayId + " to " + width + "x" + height
                            + "/" + dpi + " (no reader)");
                    try {
                        ServiceManager.getWindowManager().freezeRotation(displayId, Surface.ROTATION_0);
                    } catch (Throwable ignore) {
                        Ln.w("VirtualDisplayRegistry: freezeRotation failed (best-effort) for displayId=" + displayId, ignore);
                    }
                    return true;
                }

                // The ImageReader is the active surface. Recreate it atomically to avoid a
                // no-surface window that would drop frames rendered during the swap.
                //
                // Order: create new reader -> bind new surface -> resize -> close old reader.
                // The old surface is released only AFTER the new one is bound, so the display
                // always has a valid render target. (The previous implementation closed the old
                // reader before creating the new one, leaving a frame-drop window.)
                ImageReader newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
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
                    // Re-freeze rotation to keep virtual display orientation isolated
                    try {
                        ServiceManager.getWindowManager().freezeRotation(displayId, Surface.ROTATION_0);
                    } catch (Throwable ignore) {
                        Ln.w("VirtualDisplayRegistry: freezeRotation failed (best-effort) for displayId=" + displayId, ignore);
                    }
                } catch (Exception e) {
                    try {
                        newReader.close();
                    } catch (Exception ignore) {
                        Ln.d("VirtualDisplayRegistry: failed to close newReader during resize failure cleanup: " + ignore.getMessage());
                    }
                    throw e;
                }

                session.setImageReader(newReader);
                try {
                    oldReader.close();
                } catch (Exception ignore) {
                    Ln.d("VirtualDisplayRegistry: failed to close oldReader after atomic swap: " + ignore.getMessage());
                }

                Ln.i("VirtualDisplayRegistry: resized virtual display id=" + displayId + " to " + width + "x" + height
                        + "/" + dpi + " (reader swapped atomically)");
                return true;
            } catch (Exception e) {
                Ln.e("VirtualDisplayRegistry: failed to resize virtual display id=" + displayId, e);
                return false;
            }
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

    /**
     * Snapshot of the active virtual display ids with their live DisplayInfo
     * (width/height/dpi/rotation). The ids are snapshotted under the registry
     * lock, then each DisplayInfo is queried outside the lock to avoid holding
     * it during reflection / dumpsys fallback inside DisplayManager.
     *
     * @return array of DisplayInfo, one per active virtual display; entries
     *         whose DisplayInfo cannot be resolved are omitted
     */
    public com.genymobile.scrcpy.display.DisplayInfo[] getActiveDisplayInfos() {
        int[] ids;
        synchronized (activeDisplaysLock) {
            ids = new int[activeSessions.size()];
            int i = 0;
            for (int id : activeSessions.keySet()) {
                ids[i++] = id;
            }
        }
        java.util.List<com.genymobile.scrcpy.display.DisplayInfo> result = new java.util.ArrayList<>(ids.length);
        for (int id : ids) {
            try {
                com.genymobile.scrcpy.display.DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(id);
                if (info != null) {
                    result.add(info);
                } else {
                    Ln.w("VirtualDisplayRegistry: getDisplayInfo returned null for displayId=" + id);
                }
            } catch (Throwable t) {
                Ln.w("VirtualDisplayRegistry: failed to query DisplayInfo for displayId=" + id, t);
            }
        }
        return result.toArray(new com.genymobile.scrcpy.display.DisplayInfo[0]);
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

    /**
     * Freeze a display's rotation and verify the freeze took effect, retrying a
     * few times to bridge the race where the WindowManager has not yet registered
     * a newly-created or just-resized display (freezeRotation would silently fail
     * in that case — WindowManager catches the exception internally).
     *
     * <p>This is a daemon-side workaround; it does not modify upstream
     * {@link com.genymobile.scrcpy.wrappers.WindowManager}.
     */
    private void freezeRotationWithRetry(int displayId, int rotation) {
        com.genymobile.scrcpy.wrappers.WindowManager wm = ServiceManager.getWindowManager();
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                wm.freezeRotation(displayId, rotation);
            } catch (Throwable t) {
                Ln.w("VirtualDisplayRegistry: freezeRotation attempt " + attempt
                        + " threw for displayId=" + displayId, t);
            }
            try {
                if (wm.isRotationFrozen(displayId)) {
                    Ln.i("VirtualDisplayRegistry: froze rotation (ROTATION_" + rotation
                            + ") for display id=" + displayId + " (attempt " + attempt + ")");
                    return;
                }
            } catch (Throwable t) {
                // isRotationFrozen not supported on this version — assume freeze succeeded.
                Ln.i("VirtualDisplayRegistry: freezeRotation completed for display id=" + displayId
                        + " (isRotationFrozen check unavailable, attempt " + attempt + ")");
                return;
            }
            try {
                Thread.sleep(100 * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Ln.w("VirtualDisplayRegistry: could not confirm rotation freeze for displayId="
                + displayId + " after 5 attempts (continuing — freeze may still apply asynchronously)");
    }
}
