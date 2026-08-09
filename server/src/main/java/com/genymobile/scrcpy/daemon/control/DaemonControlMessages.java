package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;

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

    // Rotation control (per-display). For TYPE_FREEZE_ROTATION the requested
    // rotation (0-3) is carried in ControlMessage.flags to avoid adding a new
    // carrier field to the upstream ControlMessage class.
    public static final int TYPE_GET_ROTATION = 211;
    public static final int TYPE_FREEZE_ROTATION = 212;
    public static final int TYPE_THAW_ROTATION = 213;
    public static final int TYPE_IS_ROTATION_FROZEN = 214;

    // Enriched active-display query: returns per-display {id,w,h,dpi,rotation}
    // via DaemonDeviceMessages.TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS (102).
    // The legacy TYPE_GET_ACTIVE_DISPLAY_IDS (205) / 101 path is kept unchanged.
    public static final int TYPE_GET_ACTIVE_DISPLAY_INFOS = 215;

    private DaemonControlMessages() {
    }

    public static ControlMessage createCreateVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_CREATE_VIRTUAL_DISPLAY);
        msg.text = name;
        msg.width = width;
        msg.height = height;
        msg.dpi = dpi;
        msg.flags = flags;
        return msg;
    }

    public static ControlMessage createReleaseVirtualDisplay(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_RELEASE_VIRTUAL_DISPLAY);
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createResizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_RESIZE_VIRTUAL_DISPLAY);
        msg.displayId = displayId;
        msg.width = width;
        msg.height = height;
        msg.dpi = dpi;
        return msg;
    }

    public static ControlMessage createStartActivity(String packageName, int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_START_ACTIVITY);
        msg.text = packageName;
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createInjectInputEventWithDisplayId(int displayId, boolean isKeyEvent, byte[] parcelBytes) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID);
        msg.displayId = displayId;
        msg.isKeyEvent = isKeyEvent;
        msg.data = parcelBytes;
        return msg;
    }

    public static ControlMessage createSwitchDisplay(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_SWITCH_DISPLAY);
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createStartVideoStream(long sequence, int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_START_VIDEO_STREAM);
        msg.displayId = displayId;
        msg.sequence = sequence;
        return msg;
    }

    public static ControlMessage createStopVideoStream(long sequence) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_STOP_VIDEO_STREAM);
        msg.sequence = sequence;
        return msg;
    }

    public static ControlMessage createGetRotation(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_GET_ROTATION);
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createFreezeRotation(int displayId, int rotation) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_FREEZE_ROTATION);
        msg.displayId = displayId;
        // rotation (0-3) carried in flags (see type comment).
        msg.flags = rotation;
        return msg;
    }

    public static ControlMessage createThawRotation(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_THAW_ROTATION);
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createIsRotationFrozen(int displayId) {
        ControlMessage msg = ControlMessage.createEmpty(TYPE_IS_ROTATION_FROZEN);
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createGetActiveDisplayInfos() {
        return ControlMessage.createEmpty(TYPE_GET_ACTIVE_DISPLAY_INFOS);
    }
}
