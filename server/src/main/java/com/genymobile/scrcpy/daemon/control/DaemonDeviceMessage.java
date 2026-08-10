package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.DeviceApp;

import java.util.List;

/**
 * Carrier for daemon-mode device response message fields.
 *
 * <p>Daemon responses reuse the upstream {@link com.genymobile.scrcpy.control.DeviceMessage} as
 * their envelope, but their type-specific payload is stored here and attached via
 * {@code DeviceMessage.setExtensionPayload(...)}. This keeps the upstream class free of
 * daemon-specific fields, so upstream updates merge cleanly.
 */
public final class DaemonDeviceMessage {

    private long sequence;
    private String text;            // response string for generic responses
    private int statusCode;
    private int displayId;
    private int[] displayIds;
    private DisplayInfo[] displayInfos;
    private List<DeviceApp> apps;

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public int getDisplayId() {
        return displayId;
    }

    public void setDisplayId(int displayId) {
        this.displayId = displayId;
    }

    public int[] getDisplayIds() {
        return displayIds;
    }

    public void setDisplayIds(int[] displayIds) {
        this.displayIds = displayIds;
    }

    public DisplayInfo[] getDisplayInfos() {
        return displayInfos;
    }

    public void setDisplayInfos(DisplayInfo[] displayInfos) {
        this.displayInfos = displayInfos;
    }

    public List<DeviceApp> getApps() {
        return apps;
    }

    public void setApps(List<DeviceApp> apps) {
        this.apps = apps;
    }
}
