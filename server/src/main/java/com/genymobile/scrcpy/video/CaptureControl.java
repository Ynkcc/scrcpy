package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.os.Bundle;

public class CaptureControl {

    public static final int RESET_REASON_TERMINATED = 1;
    public static final int RESET_REASON_DISPLAY_PROPERTIES_CHANGED = 1 << 1;
    public static final int RESET_REASON_CLIENT_RESET = 1 << 2;
    public static final int RESET_REASON_CLIENT_RESIZED = 1 << 3;

    private int reset = 0;

    // Current instance of MediaCodec to "interrupt" on reset
    private MediaCodec runningMediaCodec;

    public synchronized boolean isResetRequested() {
        return reset != 0;
    }

    public synchronized int consumeReset() {
        int value = reset;
        reset = 0;
        return value;
    }

    public synchronized void reset(int reason) {
        assert reason != 0;
        reset |= reason;
        if (runningMediaCodec != null) {
            try {
                runningMediaCodec.signalEndOfInputStream();
            } catch (IllegalStateException e) {
                // ignore
            }
        }
    }

    public synchronized void setRunningMediaCodec(MediaCodec runningMediaCodec) {
        this.runningMediaCodec = runningMediaCodec;
    }

    /**
     * Request an instantaneous sync frame (IDR) from the running encoder.
     *
     * <p>Used by the daemon {@code FrameBroadcaster} when a new subscriber
     * joins: the new client must receive a key frame (after the cached CSD) to
     * start decoding correctly (refs/13 §4C). No-op if no encoder is running
     * or the API level does not support {@link MediaCodec#PARAMETER_KEY_REQUEST_SYNC_FRAME}.
     */
    public synchronized void requestSyncFrame() {
        if (runningMediaCodec == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < AndroidVersions.API_23_ANDROID_6_0) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            runningMediaCodec.setParameters(params);
        } catch (Throwable t) {
            Ln.w("CaptureControl: requestSyncFrame failed: " + t.getMessage());
        }
    }
}

