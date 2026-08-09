package com.genymobile.scrcpy.control;

public final class DaemonControlMessages {

    public static final int TYPE_CREATE_VIRTUAL_DISPLAY = 201;
    public static final int TYPE_RELEASE_VIRTUAL_DISPLAY = 202;
    public static final int TYPE_RESIZE_VIRTUAL_DISPLAY = 203;
    public static final int TYPE_START_ACTIVITY = 204;
    public static final int TYPE_GET_ACTIVE_DISPLAY_IDS = 205;
    public static final int TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID = 206;
    public static final int TYPE_SWITCH_DISPLAY = 207;
    public static final int TYPE_EXIT_DAEMON = 208;
    public static final int TYPE_START_VIDEO_STREAM = 209;
    public static final int TYPE_STOP_VIDEO_STREAM = 210;

    private DaemonControlMessages() {
    }

    public static ControlMessage createCreateVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_CREATE_VIRTUAL_DISPLAY);
        msg.text = name;
        msg.width = width;
        msg.height = height;
        msg.setDpi(dpi);
        msg.setFlags(flags);
        return msg;
    }

    public static ControlMessage createReleaseVirtualDisplay(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_RELEASE_VIRTUAL_DISPLAY);
        msg.setDisplayId(displayId);
        return msg;
    }

    public static ControlMessage createResizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_RESIZE_VIRTUAL_DISPLAY);
        msg.setDisplayId(displayId);
        msg.width = width;
        msg.height = height;
        msg.setDpi(dpi);
        return msg;
    }

    public static ControlMessage createStartActivity(String packageName, int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_START_ACTIVITY);
        msg.text = packageName;
        msg.setDisplayId(displayId);
        return msg;
    }

    public static ControlMessage createInjectInputEventWithDisplayId(int displayId, boolean isKeyEvent, byte[] parcelBytes) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID);
        msg.setDisplayId(displayId);
        msg.setKeyEvent(isKeyEvent);
        msg.data = parcelBytes;
        return msg;
    }

    public static ControlMessage createSwitchDisplay(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_SWITCH_DISPLAY);
        msg.setDisplayId(displayId);
        return msg;
    }

    public static ControlMessage createStartVideoStream(long sequence, int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_START_VIDEO_STREAM);
        msg.setDisplayId(displayId);
        msg.setSequence(sequence);
        return msg;
    }

    public static ControlMessage createStopVideoStream(long sequence) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_STOP_VIDEO_STREAM);
        msg.setSequence(sequence);
        return msg;
    }
}
