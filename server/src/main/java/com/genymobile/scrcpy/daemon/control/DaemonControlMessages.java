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
    // rotation (0-3) is carried in DaemonControlMessage.flags.
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

    public static DaemonControlMessage payload(ControlMessage msg) {
        return (DaemonControlMessage) msg.getExtensionPayload();
    }

    private static ControlMessage envelope(int type, DaemonControlMessage dto) {
        ControlMessage msg = ControlMessage.createEmpty(type);
        msg.setExtensionPayload(dto);
        return msg;
    }

    public static ControlMessage createCreateVirtualDisplay(long sequence, String name, int width, int height, int dpi, int flags) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setText(name);
        dto.setWidth(width);
        dto.setHeight(height);
        dto.setDpi(dpi);
        dto.setFlags(flags);
        return envelope(TYPE_CREATE_VIRTUAL_DISPLAY, dto);
    }

    public static ControlMessage createReleaseVirtualDisplay(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_RELEASE_VIRTUAL_DISPLAY, dto);
    }

    public static ControlMessage createResizeVirtualDisplay(long sequence, int displayId, int width, int height, int dpi) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        dto.setWidth(width);
        dto.setHeight(height);
        dto.setDpi(dpi);
        return envelope(TYPE_RESIZE_VIRTUAL_DISPLAY, dto);
    }

    public static ControlMessage createStartActivity(long sequence, String packageName, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setText(packageName);
        dto.setDisplayId(displayId);
        return envelope(TYPE_START_ACTIVITY, dto);
    }

    public static ControlMessage createGetActiveDisplayIds(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_GET_ACTIVE_DISPLAY_IDS, dto);
    }

    public static ControlMessage createInjectInputEventWithDisplayId(long sequence, int displayId, boolean isKeyEvent, byte[] parcelBytes) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        dto.setKeyEvent(isKeyEvent);
        dto.setData(parcelBytes);
        return envelope(TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, dto);
    }

    public static ControlMessage createSwitchDisplay(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_SWITCH_DISPLAY, dto);
    }

    public static ControlMessage createStartVideoStream(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_START_VIDEO_STREAM, dto);
    }

    public static ControlMessage createStopVideoStream(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_STOP_VIDEO_STREAM, dto);
    }

    public static ControlMessage createGetRotation(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_GET_ROTATION, dto);
    }

    public static ControlMessage createFreezeRotation(long sequence, int displayId, int rotation) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        dto.setFlags(rotation);
        return envelope(TYPE_FREEZE_ROTATION, dto);
    }

    public static ControlMessage createThawRotation(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_THAW_ROTATION, dto);
    }

    public static ControlMessage createIsRotationFrozen(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_IS_ROTATION_FROZEN, dto);
    }

    public static ControlMessage createGetActiveDisplayInfos(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_GET_ACTIVE_DISPLAY_INFOS, dto);
    }

    public static ControlMessage createExitDaemon(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_EXIT_DAEMON, dto);
    }
}
