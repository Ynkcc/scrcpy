package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.display.DisplayInfo;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;
    public static final int TYPE_UHID_OUTPUT = 2;

    public int type;
    public String text;
    public long sequence;
    private int id;
    private byte[] data;

    public int statusCode;
    public int displayId;
    public int[] displayIds;
    // Per-display metadata for TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS (102).
    public DisplayInfo[] displayInfos;

    public DeviceMessage() {
    }

    public static DeviceMessage createClipboard(String text) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_CLIPBOARD;
        event.text = text;
        return event;
    }

    public static DeviceMessage createAckClipboard(long sequence) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_ACK_CLIPBOARD;
        event.sequence = sequence;
        return event;
    }

    public static DeviceMessage createUhidOutput(int id, byte[] data) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_UHID_OUTPUT;
        event.id = id;
        event.data = data;
        return event;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public long getSequence() {
        return sequence;
    }

    public int getId() {
        return id;
    }

    public byte[] getData() {
        return data;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public int getDisplayId() {
        return displayId;
    }

    public int[] getDisplayIds() {
        return displayIds;
    }

    public DisplayInfo[] getDisplayInfos() {
        return displayInfos;
    }
}
