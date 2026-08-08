package com.genymobile.scrcpy.control;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;
    public static final int TYPE_UHID_OUTPUT = 2;

    // Daemon device responses (100+)
    public static final int TYPE_RESPONSE_GENERIC = 100;
    public static final int TYPE_RESPONSE_ACTIVE_DISPLAYS = 101;

    private int type;
    private String text;
    private long sequence;
    private int id;
    private byte[] data;

    // Daemon response fields
    private int statusCode;
    private int displayId;
    private int[] displayIds;

    private DeviceMessage() {
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

    public static DeviceMessage createGenericResponse(long sequence, int statusCode, int displayId, String responseString) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_RESPONSE_GENERIC;
        event.sequence = sequence;
        event.statusCode = statusCode;
        event.displayId = displayId;
        event.text = responseString;
        return event;
    }

    public static DeviceMessage createActiveDisplaysResponse(long sequence, int[] displayIds) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_RESPONSE_ACTIVE_DISPLAYS;
        event.sequence = sequence;
        event.displayIds = displayIds;
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
}
