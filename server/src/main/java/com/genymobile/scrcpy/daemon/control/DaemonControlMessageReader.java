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
            case DaemonControlMessages.TYPE_EXIT_DAEMON:
                return DaemonControlMessages.createExitDaemon(dis.readLong());
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
            case DaemonControlMessages.TYPE_CONFIGURE_SESSION:
                return parseConfigureSession(dis);
            case DaemonControlMessages.TYPE_LAUNCH_HOME:
                return DaemonControlMessages.createLaunchHome(dis.readLong(), dis.readInt());
            case DaemonControlMessages.TYPE_LIST_APPS:
                return DaemonControlMessages.createListApps(dis.readLong());
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
        int displayId = -1;
        try {
            displayId = dis.readInt();
        } catch (Exception ignored) {
        }
        return DaemonControlMessages.createCreateVirtualDisplay(sequence, name, width, height, dpi, flags, displayId);
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

    private static ControlMessage parseConfigureSession(DataInputStream dis) throws IOException {
        long sequence = dis.readLong();
        String optionsKv = parseString(dis);
        int rolesMask = dis.readInt();
        // Optional tail: int32 entriesCount, then (int8 role, int32 displayId)
        // for each. The legacy wire format did not include the tail; a short
        // read on the count (no more bytes available) falls back to the mask
        // only, keeping backward compat with clients that don't send entries.
        int entriesCount;
        try {
            entriesCount = dis.readInt();
        } catch (IOException e) {
            // Short read — treat as legacy mask-only payload.
            return DaemonControlMessages.createConfigureSession(sequence, optionsKv, rolesMask);
        }
        if (entriesCount <= 0 || entriesCount > 64) {
            // 0 entries valid (explicit mask-only); >64 is clearly garbage.
            return DaemonControlMessages.createConfigureSession(sequence, optionsKv, rolesMask);
        }
        java.util.List<DaemonControlMessage.RoleEntry> entries = new java.util.ArrayList<>(entriesCount);
        for (int i = 0; i < entriesCount; i++) {
            int role = dis.readUnsignedByte();
            int displayId = readInt32(dis);
            entries.add(new DaemonControlMessage.RoleEntry(role, displayId));
        }
        return DaemonControlMessages.createConfigureSession(sequence, optionsKv, rolesMask, entries);
    }

    private static int readInt32(DataInputStream dis) throws IOException {
        int b1 = dis.readUnsignedByte();
        int b2 = dis.readUnsignedByte();
        int b3 = dis.readUnsignedByte();
        int b4 = dis.readUnsignedByte();
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }
}
