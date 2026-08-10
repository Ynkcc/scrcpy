package com.genymobile.scrcpy.daemon.video;

import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One client's subscription to a {@link FrameBroadcaster}: a private bounded
 * queue + a private write thread that drains it onto the subscriber's own
 * socket (wrapped by a {@link Streamer}).
 *
 * <p>This is the per-client half of the "encode once, distribute to many"
 * model (refs/13 §3): the shared encoder never blocks on a socket; instead
 * each subscriber absorbs back-pressure locally.
 *
 * <h3>Slow-subscriber policy (refs/13 §3, A3)</h3>
 * The queue is bounded (default 30 frames). When full, {@link #deliver} drops
 * the oldest <em>droppable</em> frame (a non-keyframe, non-config packet). If
 * no droppable frame exists — i.e. dropping would lose a keyframe or essential
 * header/meta/CSD — the subscriber is considered irrecoverably behind and is
 * {@link #close() closed}. A slow client never stalls the encoder or other
 * subscribers.
 *
 * <h3>FD ownership</h3>
 * The {@link Streamer}'s file descriptor is owned by the client session's
 * connection; this class does NOT close it. On socket errors the write thread
 * simply stops and {@link #isAlive()} turns false so the broadcaster can reap
 * the dead subscriber.
 */
public final class VideoSubscriber {

    static final int QUEUE_CAPACITY = 30;

    private final int sessionId;
    private final Streamer streamer;
    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile boolean closed = false;
    private Thread writeThread;

    VideoSubscriber(int sessionId, Streamer streamer) {
        this.sessionId = sessionId;
        this.streamer = streamer;
    }

    /** Launch the dedicated write thread. Called once after construction. */
    void start() {
        writeThread = new Thread(this::writeLoop, "video-sink-" + sessionId);
        writeThread.setDaemon(true);
        writeThread.start();
    }

    /**
     * Non-blocking enqueue of a frame, applying the slow-subscriber drop policy.
     * Called only from the broadcaster's single encode thread.
     */
    void deliver(Frame frame) {
        if (closed || !alive.get()) {
            return;
        }
        if (queue.offer(frame)) {
            return;
        }
        // Queue full: try to evict the oldest droppable frame to make room.
        if (!evictOldestDroppable()) {
            // Nothing droppable — every queued frame is essential (keyframe /
            // config / header / meta). Accepting `frame` would require dropping
            // a keyframe, which would corrupt playback. Close the subscriber.
            Ln.w("VideoSubscriber[" + sessionId + "]: queue full and no droppable frame "
                    + "(would lose a keyframe) — closing slow subscriber");
            close();
            return;
        }
        // After eviction there must be room; best-effort re-offer.
        if (!queue.offer(frame)) {
            // Extremely unlikely (concurrent re-drain), but stay safe.
            Ln.w("VideoSubscriber[" + sessionId + "]: offer failed post-eviction — dropping frame " + frame);
        }
    }

    /**
     * Drain the queue, drop the first droppable frame, re-queue the rest.
     *
     * @return {@code true} if a droppable frame was found and removed
     */
    private boolean evictOldestDroppable() {
        java.util.Iterator<Frame> it = queue.iterator();
        while (it.hasNext()) {
            Frame f = it.next();
            if (f.isDroppable()) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void writeLoop() {
        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                Frame f = queue.poll(100, TimeUnit.MILLISECONDS);
                if (f == null) {
                    continue;
                }
                writeFrame(f);
                if (!alive.get()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.set(false);
        }
    }

    private void writeFrame(Frame f) {
        try {
            switch (f.kind) {
                case Frame.KIND_HEADER:
                    streamer.writeVideoHeader();
                    break;
                case Frame.KIND_META:
                    streamer.writeSessionMeta(f.width, f.height, f.isClientResize);
                    break;
                case Frame.KIND_PACKET:
                    ByteBuffer buf = ByteBuffer.wrap(f.data, 0, f.length);
                    streamer.writePacket(buf, f.pts, f.config, f.keyFrame);
                    break;
                default:
                    Ln.w("VideoSubscriber[" + sessionId + "]: unknown frame kind " + f.kind);
                    break;
            }
        } catch (IOException e) {
            // Broken pipe is expected when the client disconnects.
            if (!IO.isBrokenPipe(e)) {
                Ln.w("VideoSubscriber[" + sessionId + "]: write error: " + e.getMessage());
            }
            alive.set(false);
        }
    }

    /**
     * Stop the write thread. Does NOT close the underlying socket FD — that is
     * owned by the client session's connection.
     */
    void close() {
        closed = true;
        if (writeThread != null) {
            writeThread.interrupt();
        }
        queue.clear();
    }

    /** @return {@code true} while the write thread is alive and no error has occurred. */
    boolean isAlive() {
        return alive.get() && !closed;
    }

    int getSessionId() {
        return sessionId;
    }
}
