package com.genymobile.scrcpy.daemon.control;

/**
 * Carrier for daemon-mode control message fields.
 *
 * <p>Daemon messages reuse the upstream {@link com.genymobile.scrcpy.control.ControlMessage} as
 * their envelope (so they flow through the existing {@code ControlMessageReader} →
 * {@code Controller} pipeline), but their type-specific payload is stored here and attached via
 * {@code ControlMessage.setExtensionPayload(...)}. This keeps the upstream class free of
 * daemon-specific fields, so upstream updates merge cleanly.
 */
public final class DaemonControlMessage {

    private long sequence;
    private String text;       // display name (create) or package name (start activity)
    private int displayId;
    private int width;
    private int height;
    private int dpi;
    private int flags;         // virtual-display flags, or rotation value for freeze
    private boolean isKeyEvent;
    private byte[] data;       // marshalled InputEvent parcel bytes

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getDisplayId() {
        return displayId;
    }

    public void setDisplayId(int displayId) {
        this.displayId = displayId;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getDpi() {
        return dpi;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public boolean isKeyEvent() {
        return isKeyEvent;
    }

    public void setKeyEvent(boolean keyEvent) {
        isKeyEvent = keyEvent;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
