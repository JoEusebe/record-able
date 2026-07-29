package dev.recordable;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V1-0.08 Performance: a lightweight pool of reusable {@code byte[]} frame buffers.
 *
 * <p>The recording pipeline allocates a fresh RGB byte array for every captured
 * frame (and another when streamer-mode censoring needs a working copy). At 60 FPS
 * and 1080p that is roughly {@code 6 MB * 60 = 360 MB/s} of short-lived garbage,
 * which forces frequent young-generation GC pauses and visible stutter.</p>
 *
 * <p>This pool recycles equally sized buffers so the steady state allocates almost
 * nothing. It is intentionally simple and fully thread-safe:</p>
 * <ul>
 *   <li>Buffers of a different size than the current frame are discarded (a
 *       resolution change invalidates old buffers).</li>
 *   <li>The pool is capped so it can never itself become a memory leak.</li>
 *   <li>When pooling is disabled in config it transparently falls back to plain
 *       {@code new byte[size]} allocation.</li>
 * </ul>
 */
public final class FrameBufferPool {

    private static final FrameBufferPool INSTANCE = new FrameBufferPool();

    /** Maximum number of buffers kept around at once (bounds memory use). */
    private static final int MAX_POOLED = 6;

    private final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooledCount = new AtomicInteger(0);
    private volatile int currentSize = -1;
    private volatile boolean enabled = true;

    private FrameBufferPool() {}

    public static FrameBufferPool getInstance() {
        return INSTANCE;
    }

    /** Enables or disables pooling. When disabled, the pool is cleared. */
    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) {
            clear();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a buffer of exactly {@code size} bytes, reusing a pooled one when
     * possible. The contents are not cleared - callers always overwrite the buffer.
     */
    public byte[] acquire(int size) {
        if (!enabled || size <= 0) {
            return new byte[Math.max(0, size)];
        }
        // A size change (resolution change) invalidates every pooled buffer.
        if (size != currentSize) {
            clear();
            currentSize = size;
        }
        byte[] buf = pool.poll();
        if (buf != null) {
            pooledCount.decrementAndGet();
            if (buf.length == size) {
                return buf;
            }
            // Stale size - drop it and allocate fresh.
        }
        return new byte[size];
    }

    /**
     * Returns a buffer to the pool for reuse. Buffers of the wrong size, null
     * buffers, or overflow beyond {@link #MAX_POOLED} are simply dropped (left to GC).
     */
    public void release(byte[] buffer) {
        if (!enabled || buffer == null || buffer.length != currentSize) {
            return;
        }
        if (pooledCount.get() >= MAX_POOLED) {
            return;
        }
        pool.offer(buffer);
        pooledCount.incrementAndGet();
    }

    /** Drops all pooled buffers. Called on resolution change or when disabled. */
    public void clear() {
        pool.clear();
        pooledCount.set(0);
    }

    public int getPooledCount() {
        return pooledCount.get();
    }
}
