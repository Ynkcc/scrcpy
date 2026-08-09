package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.DeviceMessage;
import com.genymobile.scrcpy.display.DisplayInfo;

public final class DaemonDeviceMessages {

    public static final int TYPE_RESPONSE_GENERIC = 100;
    public static final int TYPE_RESPONSE_ACTIVE_DISPLAYS = 101;
    // Enriched variant of 101: carries per-display {id,width,height,dpi,rotation}.
    public static final int TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS = 102;

    private DaemonDeviceMessages() {
    }

    public static DaemonDeviceMessage payload(DeviceMessage msg) {
        return (DaemonDeviceMessage) msg.getExtensionPayload();
    }

    private static DeviceMessage envelope(int type, DaemonDeviceMessage dto) {
        DeviceMessage msg = DeviceMessage.createEmpty(type);
        msg.setExtensionPayload(dto);
        return msg;
    }

    public static DeviceMessage createGenericResponse(long sequence, int statusCode, int displayId, String responseString) {
        DaemonDeviceMessage dto = new DaemonDeviceMessage();
        dto.setSequence(sequence);
        dto.setStatusCode(statusCode);
        dto.setDisplayId(displayId);
        dto.setText(responseString);
        return envelope(TYPE_RESPONSE_GENERIC, dto);
    }

    public static DeviceMessage createActiveDisplaysResponse(long sequence, int[] displayIds) {
        DaemonDeviceMessage dto = new DaemonDeviceMessage();
        dto.setSequence(sequence);
        dto.setDisplayIds(displayIds);
        return envelope(TYPE_RESPONSE_ACTIVE_DISPLAYS, dto);
    }

    public static DeviceMessage createActiveDisplayInfosResponse(long sequence, DisplayInfo[] infos) {
        DaemonDeviceMessage dto = new DaemonDeviceMessage();
        dto.setSequence(sequence);
        dto.setDisplayInfos(infos);
        return envelope(TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS, dto);
    }
}
