package com.genymobile.scrcpy.control;

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
            case DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_IDS: {
                long sequence = dis.readLong();
                ControlMessage msg = ControlMessage.createEmpty(type);
                msg.sequence = sequence;
                return msg;
            }
            case DaemonControlMessages.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID:
                return parseInjectInputEventWithDisplayId(dis);
            case DaemonControlMessages.TYPE_SWITCH_DISPLAY:
                return parseSwitchDisplay(dis);
            case DaemonControlMessages.TYPE_EXIT_DAEMON: {
                long sequence = dis.readLong();
                ControlMessage msg = ControlMessage.createEmpty(type);
                msg.sequence = sequence;
                return msg;
            }
            case DaemonControlMessages.TYPE_START_VIDEO_STREAM: {
                long sequence = dis.readLong();
                int displayId = dis.readInt();
                return DaemonControlMessages.createStartVideoStream(sequence, displayId);
            }
            case DaemonControlMessages.TYPE_STOP_VIDEO_STREAM: {
                long sequence = dis.readLong();
                return DaemonControlMessages.createStopVideoStream(sequence);
            }
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
        ControlMessage msg = DaemonControlMessages.createCreateVirtualDisplay(name, width, height, dpi, flags);
        msg.sequence = sequence;
        return msg;
    }

    private static ControlMessage parseReleaseVirtualDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        ControlMessage msg = DaemonControlMessages.createReleaseVirtualDisplay(displayId);
        msg.sequence = sequence;
        return msg;
    }

    private static ControlMessage parseResizeVirtualDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        int width = dis.readInt();
        int height = dis.readInt();
        int dpi = dis.readInt();
        ControlMessage msg = DaemonControlMessages.createResizeVirtualDisplay(displayId, width, height, dpi);
        msg.sequence = sequence;
        return msg;
    }

    private static ControlMessage parseStartActivityWithDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        String packageName = parseString(dis);
        int displayId = dis.readInt();
        ControlMessage msg = DaemonControlMessages.createStartActivity(packageName, displayId);
        msg.sequence = sequence;
        return msg;
    }

    private static ControlMessage parseInjectInputEventWithDisplayId(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        boolean isKeyEvent = dis.readByte() != 0;
        byte[] parcelBytes = parseByteArray(dis, 4);
        ControlMessage msg = DaemonControlMessages.createInjectInputEventWithDisplayId(displayId, isKeyEvent, parcelBytes);
        msg.sequence = sequence;
        return msg;
    }

    private static ControlMessage parseSwitchDisplay(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        int displayId = dis.readInt();
        ControlMessage msg = DaemonControlMessages.createSwitchDisplay(displayId);
        msg.sequence = sequence;
        return msg;
    }
}
