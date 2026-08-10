package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.util.Ln;

import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.HandlerThread;
import android.view.Surface;

public final class VirtualDisplaySession implements AutoCloseable {

    private final int displayId;
    private final String name;
    private final VirtualDisplay virtualDisplay;
    private final HandlerThread readerThread;
    private final int mirrorDisplayId;
    
    private ImageReader imageReader;
    private Surface externalSurface;

    public VirtualDisplaySession(int displayId, String name, VirtualDisplay virtualDisplay, 
                                 ImageReader imageReader, HandlerThread readerThread) {
        this(displayId, name, virtualDisplay, imageReader, readerThread, -1);
    }

    public VirtualDisplaySession(int displayId, String name, VirtualDisplay virtualDisplay, 
                                 ImageReader imageReader, HandlerThread readerThread, int mirrorDisplayId) {
        this.displayId = displayId;
        this.name = name;
        this.virtualDisplay = virtualDisplay;
        this.imageReader = imageReader;
        this.readerThread = readerThread;
        this.mirrorDisplayId = mirrorDisplayId;
    }

    public int getMirrorDisplayId() {
        return mirrorDisplayId;
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getName() {
        return name;
    }

    public VirtualDisplay getVirtualDisplay() {
        return virtualDisplay;
    }

    public synchronized ImageReader getImageReader() {
        return imageReader;
    }

    public synchronized void setImageReader(ImageReader imageReader) {
        this.imageReader = imageReader;
    }

    public HandlerThread getReaderThread() {
        return readerThread;
    }

    public synchronized Surface getExternalSurface() {
        return externalSurface;
    }

    public synchronized void setExternalSurface(Surface surface) {
        this.externalSurface = surface;
        try {
            if (virtualDisplay != null) {
                if (surface != null) {
                    virtualDisplay.setSurface(surface);
                } else if (imageReader != null) {
                    virtualDisplay.setSurface(imageReader.getSurface());
                } else {
                    virtualDisplay.setSurface(null);
                }
            }
        } catch (Exception e) {
            Ln.e("VirtualDisplaySession: failed to set surface for displayId=" + displayId, e);
        }
    }

    public synchronized void restoreFallbackSurface() {
        try {
            if (virtualDisplay != null && imageReader != null) {
                virtualDisplay.setSurface(imageReader.getSurface());
                this.externalSurface = null;
                Ln.i("VirtualDisplaySession: restored fallback surface for displayId=" + displayId);
            }
        } catch (Exception e) {
            Ln.w("VirtualDisplaySession: failed to restore fallback surface for displayId=" + displayId, e);
        }
    }

    @Override
    public synchronized void close() {
        Ln.i("VirtualDisplaySession: closing session for displayId=" + displayId + " (" + name + ")");
        
        if (externalSurface != null) {
            try {
                externalSurface.release();
            } catch (Exception ignore) {
                Ln.d("VirtualDisplaySession: failed to release externalSurface for displayId=" + displayId + ": " + ignore.getMessage());
            }
            externalSurface = null;
        }

        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception e) {
                Ln.e("VirtualDisplaySession: failed to close ImageReader for displayId=" + displayId, e);
            }
            imageReader = null;
        }

        if (readerThread != null) {
            try {
                readerThread.quitSafely();
            } catch (Exception ignore) {
                Ln.d("VirtualDisplaySession: failed to quitSafely readerThread for displayId=" + displayId + ": " + ignore.getMessage());
            }
        }

        if (virtualDisplay != null) {
            try {
                try {
                    virtualDisplay.setSurface(null);
                } catch (Exception ignore) {
                    Ln.d("VirtualDisplaySession: failed to clear surface before release for displayId=" + displayId + ": " + ignore.getMessage());
                }
                virtualDisplay.release();
            } catch (Exception e) {
                Ln.e("VirtualDisplaySession: failed to release VirtualDisplay for displayId=" + displayId, e);
            }
        }
    }
}
