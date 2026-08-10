package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;

public final class DaemonControlMessages {

    public static final int TYPE_CREATE_VIRTUAL_DISPLAY = 201;
    public static final int TYPE_RELEASE_VIRTUAL_DISPLAY = 202;
    public static final int TYPE_RESIZE_VIRTUAL_DISPLAY = 203;
    public static final int TYPE_START_ACTIVITY = 204;
    public static final int TYPE_GET_ACTIVE_DISPLAY_IDS = 205;
    public static final int TYPE_EXIT_DAEMON = 208;

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

    // Negotiation-phase session configuration. The client MUST send this on the
    // ROLE_NEGOTIATION socket (after receiving sessionId + device meta) before
    // opening any ROLE_VIDEO / ROLE_AUDIO / ROLE_CONTROL socket. It carries
    // per-session scrcpy option overrides (newline-separated key=value) and a
    // bitmask of the roles the client intends to open. The server rebuilds the
    // per-session Options from the overrides and only then allows role sockets
    // to bind — enforcing "negotiate first, open more sockets after".
    public static final int TYPE_CONFIGURE_SESSION = 216;

    // Launch the device's default home launcher on a specific virtual display.
    // Payload: int32 displayId. Server resolves the home component itself
    // (so the client doesn't need to query the remote device's launcher list
    // — unifies local and remote node behaviour).
    public static final int TYPE_LAUNCH_HOME = 217;

    // List installed apps on the device. Returns the list of non-system apps
    // that have a launch intent, so the client AppSelectionDialog works
    // identically for both local and remote nodes.
    public static final int TYPE_LIST_APPS = 218;

    // Ping request to measure RTT. Server replies with TYPE_RESPONSE_GENERIC
    // echoing the sequence number.
    public static final int TYPE_PING = 219;

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
        return createCreateVirtualDisplay(sequence, name, width, height, dpi, flags, -1);
    }

    public static ControlMessage createCreateVirtualDisplay(long sequence, String name, int width, int height, int dpi, int flags, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setText(name);
        dto.setWidth(width);
        dto.setHeight(height);
        dto.setDpi(dpi);
        dto.setFlags(flags);
        dto.setDisplayId(displayId);
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

    public static ControlMessage createConfigureSession(long sequence, String optionsKv, int rolesMask) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setOptionsKv(optionsKv != null ? optionsKv : "");
        dto.setRolesMask(rolesMask);
        return envelope(TYPE_CONFIGURE_SESSION, dto);
    }

    /**
     * Multi-role multi-display variant of {@link #createConfigureSession(long, String, int)}.
     *
     * <p>Declares the exact set of (role, displayId) sockets the client
     * intends to open. See {@link DaemonControlMessage.RoleEntry} for
     * semantics. When non-empty, {@code rolesEntries} takes precedence over
     * the legacy 3-bit mask; the mask is preserved in the payload so older
     * readers that only inspect the mask still see which role *types* are
     * intended.
     */
    public static ControlMessage createConfigureSession(long sequence, String optionsKv, int rolesMask,
                                                        java.util.List<DaemonControlMessage.RoleEntry> rolesEntries) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setOptionsKv(optionsKv != null ? optionsKv : "");
        dto.setRolesMask(rolesMask);
        dto.setRolesEntries(rolesEntries);
        return envelope(TYPE_CONFIGURE_SESSION, dto);
    }

    public static ControlMessage createLaunchHome(long sequence, int displayId) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        dto.setDisplayId(displayId);
        return envelope(TYPE_LAUNCH_HOME, dto);
    }

    public static ControlMessage createListApps(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_LIST_APPS, dto);
    }

    public static ControlMessage createPing(long sequence) {
        DaemonControlMessage dto = new DaemonControlMessage();
        dto.setSequence(sequence);
        return envelope(TYPE_PING, dto);
    }
}
