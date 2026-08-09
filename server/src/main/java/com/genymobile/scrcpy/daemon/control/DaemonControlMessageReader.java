package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DaemonControlMessageReader {

    private DaemonControlMessageReader() {
    }

    public static ControlMessage read(int type, DataInputStream dis) throws IOException {
        switch (type) {
            case DaemonControlMessages.TYPE_CREATE_VIRTUAL_DISPLAY:
                return parseCreateVirtualDisplay(dis);
            case DaemonControlMessages.TYPE_RELEASE_VIRTUAL_DISPLAY:
                return parseReleaseVirtualDisplay(dis);
            case DaemonControlMessages.TYPE_RESIZE_VIRTUAL_DISPLAY:
                return parseResizeVirtualDisplay(dis);
            case DaemonControlMessages.TYPE_START_ACTIVITY:
                return parseStartActivityWithDisplay(dis);
            case DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_IDS:
                return DaemonControlMessages.createGetActiveDisplayIds(dis.readLong());
            case DaemonControlMessages.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID:
                return parseInjectInputEventWithDisplayId(dis);
            case DaemonControlMessages.TYPE_SWITCH_DISPLAY:
                return parseSwitchDisplay(dis);
            case DaemonControlMessages.TYPE_EXIT_DAEMON:
                return DaemonControlMessages.createExitDaemon(dis.readLong());
            case DaemonControlMessages.TYPE_START_VIDEO_STREAM:
                return DaemonControlMessages.createStartVideoStream(dis.readLong(), dis.readInt());
            case DaemonControlMessages.TYPE_STOP_VIDEO_STREAM:
                return DaemonControlMessages.createStopVideoStream(dis.readLong());
            case DaemonControlMessages.TYPE_GET_ROTATION:
                return DaemonControlMessages.createGetRotation(dis.readLong(), dis.readInt());
            case DaemonControlMessages.TYPE_FREEZE_ROTATION:
                return DaemonControlMessages.createFreezeRotation(dis.readLong(), dis.readInt(), dis.readInt());
            case DaemonControlMessages.TYPE_THAW_ROTATION:
                return DaemonControlMessages.createThawRotation(dis.readLong(), dis.readInt());
            case DaemonControlMessages.TYPE_IS_ROTATION_FROZEN:
                return DaemonControlMessages.createIsRotationFrozen(dis.readLong(), dis.readInt());
            case DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_INFOS:
                return DaemonControlMessages.createGetActiveDisplayInfos(dis.readLong());
            default:
                return null;
        }
    }

    private static String parseString(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        byte[] data = new byte[len];
        dis.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] parseByteArray(DataInputStream dis, int sizeBytes) throws IOException {
        int len = 0;
        for (int i = 0; i < sizeBytes; ++i) {
            len = (len << 8) | dis.readUnsignedByte();
        }
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }

    private static ControlMessage parseCreateVirtualDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        String name = parseString(dis);
        int width = dis.readInt();
        int height = dis.readInt();
        int dpi = dis.readInt();
        int flags = dis.readInt();
        return DaemonControlMessages.createCreateVirtualDisplay(sequence, name, width, height, dpi, flags);
    }

    private static ControlMessage parseReleaseVirtualDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        return DaemonControlMessages.createReleaseVirtualDisplay(sequence, displayId);
    }

    private static ControlMessage parseResizeVirtualDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        int width = dis.readInt();
        int height = dis.readInt();
        int dpi = dis.readInt();
        return DaemonControlMessages.createResizeVirtualDisplay(sequence, displayId, width, height, dpi);
    }

    private static ControlMessage parseStartActivityWithDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        String packageName = parseString(dis);
        int displayId = dis.readInt();
        return DaemonControlMessages.createStartActivity(sequence, packageName, displayId);
    }

    private static ControlMessage parseInjectInputEventWithDisplayId(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        boolean isKeyEvent = dis.readByte() != 0;
        byte[] parcelBytes = parseByteArray(dis, 4);
        return DaemonControlMessages.createInjectInputEventWithDisplayId(sequence, displayId, isKeyEvent, parcelBytes);
    }

    private static ControlMessage parseSwitchDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        return DaemonControlMessages.createSwitchDisplay(sequence, displayId);
    }
}
