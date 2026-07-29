package dev.recordable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance Overlay metrics using lightweight Java atomics.
 * Tracks frame capture rate, encoding latency, buffer health, and dropped frames.
 * Provides real-time stats for the recording overlay.
 *
 * Previously used Micrometer; replaced with plain AtomicLong counters
 * to eliminate the heavy dependency and avoid early class-loading issues
 * in large modpacks.
 */
public final class PerformanceMetrics {

    private static final PerformanceMetrics INSTANCE = new PerformanceMetrics();

    private final AtomicLong framesCaptured = new AtomicLong(0);
    private final AtomicLong framesDropped = new AtomicLong(0);
    private final AtomicLong framesEncoded = new AtomicLong(0);
    private final AtomicLong adaptiveDrops = new AtomicLong(0);
    private final AtomicLong queueSize = new AtomicLong(0);
    private final AtomicLong queueCapacity = new AtomicLong(240);
    private final AtomicLong currentFps = new AtomicLong(0);
    private final AtomicLong encoderFps = new AtomicLong(0);
    private final AtomicLong memoryUsedMiB = new AtomicLong(0);
    private final AtomicLong fileSizeBytes = new AtomicLong(0);

    // Capture/encode latency tracking (simple rolling average)
    private final AtomicLong captureLatencySumNanos = new AtomicLong(0);
    private final AtomicLong captureLatencyCount = new AtomicLong(0);
    private final AtomicLong encodeLatencySumNanos = new AtomicLong(0);
    private final AtomicLong encodeLatencyCount = new AtomicLong(0);

    // Rolling averages for overlay display
    private volatile double avgCaptureLatencyMs = 0.0;
    private volatile double avgEncodeLatencyMs = 0.0;
    private volatile double bufferHealthPercent = 100.0;

    private PerformanceMetrics() {
    }

    public static PerformanceMetrics getInstance() {
        return INSTANCE;
    }

    public void recordFrameCapture(long durationNanos) {
        framesCaptured.incrementAndGet();
        captureLatencySumNanos.addAndGet(durationNanos);
        long count = captureLatencyCount.incrementAndGet();
        if (count > 0) {
            avgCaptureLatencyMs = (captureLatencySumNanos.get() / (double) count) / 1_000_000.0;
        }
    }

    public void recordFrameEncode(long durationNanos) {
        framesEncoded.incrementAndGet();
        encodeLatencySumNanos.addAndGet(durationNanos);
        long count = encodeLatencyCount.incrementAndGet();
        if (count > 0) {
            avgEncodeLatencyMs = (encodeLatencySumNanos.get() / (double) count) / 1_000_000.0;
        }
    }

    public void recordFrameDrop() {
        framesDropped.incrementAndGet();
    }

    public void recordAdaptiveDrop() {
        adaptiveDrops.incrementAndGet();
    }

    public void updateQueueStats(int size, int capacity) {
        queueSize.set(size);
        queueCapacity.set(capacity);
        bufferHealthPercent = capacity > 0
                ? Math.max(0.0, 100.0 - (size * 100.0 / capacity))
                : 100.0;
    }

    public void updateFps(long captureFps, long encodeFps) {
        currentFps.set(captureFps);
        encoderFps.set(encodeFps);
    }

    public void updateMemory(long usedMiB) {
        memoryUsedMiB.set(usedMiB);
    }

    public void updateFileSize(long bytes) {
        fileSizeBytes.set(bytes);
    }

    /** Resets all counters for a new recording session. */
    public void reset() {
        framesCaptured.set(0);
        framesDropped.set(0);
        framesEncoded.set(0);
        adaptiveDrops.set(0);
        captureLatencySumNanos.set(0);
        captureLatencyCount.set(0);
        encodeLatencySumNanos.set(0);
        encodeLatencyCount.set(0);
        avgCaptureLatencyMs = 0.0;
        avgEncodeLatencyMs = 0.0;
        bufferHealthPercent = 100.0;
        queueSize.set(0);
        fileSizeBytes.set(0);
    }

    // === Getters for overlay display ===

    public double getAvgCaptureLatencyMs() { return avgCaptureLatencyMs; }
    public double getAvgEncodeLatencyMs() { return avgEncodeLatencyMs; }
    public double getBufferHealthPercent() { return bufferHealthPercent; }
    public long getTotalCaptured() { return framesCaptured.get(); }
    public long getTotalDropped() { return framesDropped.get(); }
    public long getTotalEncoded() { return framesEncoded.get(); }
    public long getCaptureFps() { return currentFps.get(); }
    public long getEncoderFps() { return encoderFps.get(); }
    public long getMemoryUsedMiB() { return memoryUsedMiB.get(); }
    public long getQueueSize() { return queueSize.get(); }
    public long getQueueCapacity() { return queueCapacity.get(); }

    /**
     * Returns a compact performance summary string for the overlay.
     */
    public String getCompactSummary() {
        return String.format("Cap %.1fms | Enc %.1fms | Buf %.0f%%",
                avgCaptureLatencyMs, avgEncodeLatencyMs, bufferHealthPercent);
    }
}
