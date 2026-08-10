package com.genymobile.scrcpy.daemon.video;

import java.nio.ByteBuffer;

/**
 * Immutable, kind-tagged frame envelope exchanged between the
 * {@link FrameBroadcaster} and its {@link VideoSubscriber}s.
 *
 * <p>A single {@code Frame} is produced by the broadcaster for each event
 * emitted by the encoder (stream header, session meta, or encoded packet) and
 * is shared by reference among all subscribers (refs/13 §4B hybrid: one copy
 * off the {@code MediaCodec} buffer, many readers). The byte payload is copied
 * exactly once — at construction — because {@code MediaCodec} output buffers
 * are invalidated by {@code releaseOutputBuffer}.
 *
 * <p>The {@code kind} discriminator lets a subscriber's bounded queue apply a
 * drop policy that never discards header/meta/config packets (essential for
 * decoding) and prefers dropping non-keyframe packets when overflowing.
 */
final class Frame {

    static final int KIND_HEADER = 0; // writeVideoHeader()
    static final int KIND_META = 1;   // writeSessionMeta(w, h, isClientResize)
    static final int KIND_PACKET = 2; // writePacket(data, pts, config, keyFrame)

    final int kind;

    // KIND_PACKET only
    final byte[] data;
    final int length;
    final long pts;
    final boolean config;
    final boolean keyFrame;

    // KIND_META only
    final int width;
    final int height;
    final boolean isClientResize;

    private Frame(int kind, byte[] data, int length, long pts, boolean config, boolean keyFrame,
                  int width, int height, boolean isClientResize) {
        this.kind = kind;
        this.data = data;
        this.length = length;
        this.pts = pts;
        this.config = config;
        this.keyFrame = keyFrame;
        this.width = width;
        this.height = height;
        this.isClientResize = isClientResize;
    }

    static Frame header() {
        return new Frame(KIND_HEADER, null, 0, 0L, false, false, 0, 0, false);
    }

    static Frame meta(int width, int height, boolean isClientResize) {
        return new Frame(KIND_META, null, 0, 0L, false, false, width, height, isClientResize);
    }

    /**
     * Build a packet frame, copying {@code remaining()} bytes out of
     * {@code buffer} (via a duplicate so the source position is untouched).
     * Must be called BEFORE {@code MediaCodec.releaseOutputBuffer}.
     */
    static Frame packet(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) {
        int len = buffer.remaining();
        byte[] data = new byte[len];
        buffer.duplicate().get(data);
        return new Frame(KIND_PACKET, data, len, pts, config, keyFrame, 0, 0, false);
    }

    /**
     * @return {@code true} if this frame is essential and must not be dropped
     *         by a subscriber's overflow policy (header/meta/CSD/keyframe are
     *         all either tiny or required for decoding).
     */
    boolean isDroppable() {
        return kind == KIND_PACKET && !keyFrame && !config;
    }

    @Override
    public String toString() {
        switch (kind) {
            case KIND_HEADER: return "Frame[HEADER]";
            case KIND_META: return "Frame[META " + width + "x" + height + " resize=" + isClientResize + "]";
            case KIND_PACKET: return "Frame[PACKET len=" + length + " pts=" + pts
                    + " config=" + config + " key=" + keyFrame + "]";
            default: return "Frame[?]";
        }
    }
}
