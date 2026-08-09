package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.model.Position;

/**
 * Union of all supported event types, identified by their {@code type}.
 */
public final class ControlMessage {

    public static final int TYPE_INJECT_KEYCODE = 0;
    public static final int TYPE_INJECT_TEXT = 1;
    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_INJECT_SCROLL_EVENT = 3;
    public static final int TYPE_BACK_OR_SCREEN_ON = 4;
    public static final int TYPE_EXPAND_NOTIFICATION_PANEL = 5;
    public static final int TYPE_EXPAND_SETTINGS_PANEL = 6;
    public static final int TYPE_COLLAPSE_PANELS = 7;
    public static final int TYPE_GET_CLIPBOARD = 8;
    public static final int TYPE_SET_CLIPBOARD = 9;
    public static final int TYPE_SET_DISPLAY_POWER = 10;
    public static final int TYPE_ROTATE_DEVICE = 11;
    public static final int TYPE_UHID_CREATE = 12;
    public static final int TYPE_UHID_INPUT = 13;
    public static final int TYPE_UHID_DESTROY = 14;
    public static final int TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15;
    public static final int TYPE_START_APP = 16;
    public static final int TYPE_RESET_VIDEO = 17;
    public static final int TYPE_CAMERA_SET_TORCH = 18;
    public static final int TYPE_CAMERA_ZOOM_IN = 19;
    public static final int TYPE_CAMERA_ZOOM_OUT = 20;
    public static final int TYPE_RESIZE_DISPLAY = 21;
    public static final int TYPE_SCAN_FILE = 22;

    // Daemon control commands (200+)
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

    public static final long SEQUENCE_INVALID = 0;

    public static final int COPY_KEY_NONE = 0;
    public static final int COPY_KEY_COPY = 1;
    public static final int COPY_KEY_CUT = 2;

    private int type;
    private String text;
    private int metaState; // KeyEvent.META_*
    private int action; // KeyEvent.ACTION_* or MotionEvent.ACTION_*
    private int keycode; // KeyEvent.KEYCODE_*
    private int actionButton; // MotionEvent.BUTTON_*
    private int buttons; // MotionEvent.BUTTON_*
    private long pointerId;
    private float pressure;
    private Position position;
    private float hScroll;
    private float vScroll;
    private int copyKey;
    private boolean paste;
    private int repeat;
    private long sequence;
    private int id;
    private byte[] data;
    private boolean on;
    private int vendorId;
    private int productId;
    private int width;
    private int height;

    // Daemon-specific fields
    private int displayId;
    private int dpi;
    private int flags;
    private boolean isKeyEvent;

    private ControlMessage() {
    }

    public static ControlMessage createInjectKeycode(int action, int keycode, int repeat, int metaState) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_INJECT_KEYCODE;
        msg.action = action;
        msg.keycode = keycode;
        msg.repeat = repeat;
        msg.metaState = metaState;
        return msg;
    }

    public static ControlMessage createInjectText(String text) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_INJECT_TEXT;
        msg.text = text;
        return msg;
    }

    public static ControlMessage createInjectTouchEvent(int action, long pointerId, Position position, float pressure, int actionButton,
            int buttons) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_INJECT_TOUCH_EVENT;
        msg.action = action;
        msg.pointerId = pointerId;
        msg.pressure = pressure;
        msg.position = position;
        msg.actionButton = actionButton;
        msg.buttons = buttons;
        return msg;
    }

    public static ControlMessage createInjectScrollEvent(Position position, float hScroll, float vScroll, int buttons) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_INJECT_SCROLL_EVENT;
        msg.position = position;
        msg.hScroll = hScroll;
        msg.vScroll = vScroll;
        msg.buttons = buttons;
        return msg;
    }

    public static ControlMessage createBackOrScreenOn(int action) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_BACK_OR_SCREEN_ON;
        msg.action = action;
        return msg;
    }

    public static ControlMessage createGetClipboard(int copyKey) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_GET_CLIPBOARD;
        msg.copyKey = copyKey;
        return msg;
    }

    public static ControlMessage createSetClipboard(long sequence, String text, boolean paste) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_SET_CLIPBOARD;
        msg.sequence = sequence;
        msg.text = text;
        msg.paste = paste;
        return msg;
    }

    public static ControlMessage createSetDisplayPower(boolean on) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_SET_DISPLAY_POWER;
        msg.on = on;
        return msg;
    }

    public static ControlMessage createEmpty(int type) {
        ControlMessage msg = new ControlMessage();
        msg.type = type;
        return msg;
    }

    public static ControlMessage createUhidCreate(int id, int vendorId, int productId, String name, byte[] reportDesc) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_UHID_CREATE;
        msg.id = id;
        msg.vendorId = vendorId;
        msg.productId = productId;
        msg.text = name;
        msg.data = reportDesc;
        return msg;
    }

    public static ControlMessage createUhidInput(int id, byte[] data) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_UHID_INPUT;
        msg.id = id;
        msg.data = data;
        return msg;
    }

    public static ControlMessage createUhidDestroy(int id) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_UHID_DESTROY;
        msg.id = id;
        return msg;
    }

    public static ControlMessage createStartApp(String name) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_START_APP;
        msg.text = name;
        return msg;
    }

    public static ControlMessage createCameraSetTorch(boolean on) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_CAMERA_SET_TORCH;
        msg.on = on;
        return msg;
    }

    public static ControlMessage createResizeDisplay(int width, int height) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_RESIZE_DISPLAY;
        msg.width = width;
        msg.height = height;
        return msg;
    }

    public static ControlMessage createScanFile(String path) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_SCAN_FILE;
        msg.text = path;
        return msg;
    }

    public static ControlMessage createCreateVirtualDisplay(String name, int width, int height, int dpi, int flags) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_CREATE_VIRTUAL_DISPLAY;
        msg.text = name;
        msg.width = width;
        msg.height = height;
        msg.dpi = dpi;
        msg.flags = flags;
        return msg;
    }

    public static ControlMessage createReleaseVirtualDisplay(int displayId) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_RELEASE_VIRTUAL_DISPLAY;
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createResizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_RESIZE_VIRTUAL_DISPLAY;
        msg.displayId = displayId;
        msg.width = width;
        msg.height = height;
        msg.dpi = dpi;
        return msg;
    }

    public static ControlMessage createStartActivity(String packageName, int displayId) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_START_ACTIVITY;
        msg.text = packageName;
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createInjectInputEventWithDisplayId(int displayId, boolean isKeyEvent, byte[] parcelBytes) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID;
        msg.displayId = displayId;
        msg.isKeyEvent = isKeyEvent;
        msg.data = parcelBytes;
        return msg;
    }

    public static ControlMessage createSwitchDisplay(int displayId) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_SWITCH_DISPLAY;
        msg.displayId = displayId;
        return msg;
    }

    public static ControlMessage createStartVideoStream(long sequence, int displayId) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_START_VIDEO_STREAM;
        msg.displayId = displayId;
        msg.setSequence(sequence);
        return msg;
    }

    public static ControlMessage createStopVideoStream(long sequence) {
        ControlMessage msg = new ControlMessage();
        msg.type = TYPE_STOP_VIDEO_STREAM;
        msg.setSequence(sequence);
        return msg;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public int getMetaState() {
        return metaState;
    }

    public int getAction() {
        return action;
    }

    public int getKeycode() {
        return keycode;
    }

    public int getActionButton() {
        return actionButton;
    }

    public int getButtons() {
        return buttons;
    }

    public long getPointerId() {
        return pointerId;
    }

    public float getPressure() {
        return pressure;
    }

    public Position getPosition() {
        return position;
    }

    public float getHScroll() {
        return hScroll;
    }

    public float getVScroll() {
        return vScroll;
    }

    public int getCopyKey() {
        return copyKey;
    }

    public boolean getPaste() {
        return paste;
    }

    public int getRepeat() {
        return repeat;
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

    public boolean getOn() {
        return on;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDisplayId() {
        return displayId;
    }

    public int getDpi() {
        return dpi;
    }

    public int getFlags() {
        return flags;
    }

    public boolean isKeyEvent() {
        return isKeyEvent;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }
}
