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

    public static DeviceMessage createActiveDisplayInfosResponse(long sequence, DisplayInfo[] infos) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS;
        event.sequence = sequence;
        event.displayInfos = infos;
        return event;
    }
}
