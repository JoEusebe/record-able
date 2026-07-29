package dev.recordable;

import net.minecraft.client.MinecraftClient;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feature 7: Replay Buffer - Disk-Backed Implementation.
 *
 * <p>Maintains a circular buffer of the last N seconds of frames, allowing the
 * user to save recent gameplay retroactively.</p>
 *
 * <h3>Why disk instead of RAM (v3)</h3>
 * <p>The previous implementation kept every buffered frame in the JVM heap.
 * RAM is expensive and scarce (especially on Android), so a long buffer at a
 * decent resolution could exhaust the heap and crash the game. This version
 * streams frames to disk instead:</p>
 * <ul>
 *   <li>Raw frames are appended to rolling chunk files on disk (32 MB each)</li>
 *   <li>Only a tiny per-frame index (chunk id, offset, length, timestamp) lives
 *       in RAM - roughly 40 bytes per frame instead of megabytes</li>
 *   <li>Old frames are evicted by time / disk budget / frame count, and empty
 *       chunk files are deleted, so disk usage stays bounded</li>
 *   <li>The disk budget is far larger than the old RAM budget, so the buffer
 *       can hold much longer clips without touching the heap</li>
 * </ul>
 */
public final class ReplayBuffer {

    private static final ReplayBuffer INSTANCE = new ReplayBuffer();

    /** Roll to a new chunk file once the current one grows past this size. */
    private static final long CHUNK_MAX_BYTES = 32L * 1024L * 1024L;

    /** Lightweight reference to a frame stored on disk. */
    private record FrameRef(int chunkId, long offset, int length,
                            int width, int height, long timestampMs) {}

    /** A rolling on-disk chunk file that holds many concatenated raw frames. */
    private static final class Chunk {
        final int id;
        final Path path;
        BufferedOutputStream out;   // non-null while this chunk is the active write target
        long bytesWritten;
        int refCount;               // number of live frames still referencing this chunk

        Chunk(int id, Path path) {
            this.id = id;
            this.path = path;
        }
    }

    // Guards the on-disk index and chunk bookkeeping. addFrame() runs on the
    // render thread; saveBuffer() reads on a background thread.
    private final Object lock = new Object();
    private final Deque<FrameRef> index = new ArrayDeque<>();
    private final Map<Integer, Chunk> chunks = new HashMap<>();

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final AtomicLong currentDiskBytes = new AtomicLong(0);

    private Path bufferDir;
    private Chunk currentChunk;
    private int nextChunkId;
    // When a save is in progress we must not delete chunk files it is still
    // reading, so deletions are deferred until the save completes.
    private boolean pinnedForSaving = false;
    private final Deque<Integer> pendingChunkDeletions = new ArrayDeque<>();

    private volatile int targetFps = 60;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile int storedFrameWidth;   // actual stored resolution (may be downscaled)
    private volatile int storedFrameHeight;
    private volatile long bufferDurationMs;
    private volatile long diskBudgetBytes;
    private volatile int maxFrameCount;
    private volatile int downscaleFactor = 1;
    // Replay-quality fps cap: frames arriving faster than this interval are dropped so
    // lower presets (e.g. 30 FPS) genuinely reduce captured frames and disk use.
    private volatile int fpsCap = 60;
    private volatile long frameIntervalMs = 0L;
    private volatile long lastAcceptedFrameMs = 0L;

    private ReplayBuffer() {}

    public static ReplayBuffer getInstance() { return INSTANCE; }

    /**
     * Configures and starts the replay buffer with disk-aware limits.
     */
    public void start(int width, int height, int fps, int durationSeconds) {
        start(width, height, fps, durationSeconds, "source");
    }

    /**
     * Configures and starts the replay buffer, applying a replay-quality preset.
     *
     * <p>The {@code quality} preset (see {@link RecordableConfig#REPLAY_QUALITIES}) controls
     * both the stored resolution (via an integer downscale factor toward the preset's target
     * height) and a frame-rate cap (frames arriving faster than the cap are dropped). The
     * preset never upscales beyond the live recording resolution / frame rate.</p>
     *
     * @param quality one of {@link RecordableConfig#REPLAY_QUALITIES}
     */
    public void start(int width, int height, int fps, int durationSeconds, String quality) {
        // Platform-aware disk budget, clamped to the free space on the output volume.
        RecordableConfig config = RecordableConfig.get();
        long budgetMB = PlatformUtils.getReplayBufferDiskBudgetMB();
        long freeBytes = PlatformUtils.getFreeDiskSpaceBytes(config.getOutputDirectory());
        if (freeBytes > 0) {
            // Never claim more than 80% of the remaining free space.
            long freeBudgetMB = (freeBytes / (1024L * 1024L)) * 80 / 100;
            budgetMB = Math.min(budgetMB, Math.max(64L, freeBudgetMB));
        }
        this.diskBudgetBytes = budgetMB * 1024L * 1024L;

        // Resolve the replay-quality preset -> target height + fps cap.
        int[] preset = RecordableConfig.resolveReplayPreset(quality, height, fps);
        int targetHeight = preset[0];
        int effectiveFps = preset[1] > 0 ? Math.min(fps > 0 ? fps : preset[1], preset[1]) : (fps > 0 ? fps : 60);

        // Combine the platform (mobile) downscale with the quality downscale; never upscale.
        int platformFactor = Math.max(1, PlatformUtils.getReplayBufferDownscaleFactor());
        int qualityFactor = 1;
        if (targetHeight > 0 && height > targetHeight) {
            qualityFactor = Math.max(1, (int) Math.round(height / (double) targetHeight));
        }
        this.downscaleFactor = Math.max(platformFactor, qualityFactor);

        this.fpsCap = Math.max(1, effectiveFps);
        this.frameIntervalMs = 1000L / this.fpsCap;
        this.lastAcceptedFrameMs = 0L;

        this.frameWidth = width;
        this.frameHeight = height;
        this.storedFrameWidth = Math.max(1, width / downscaleFactor);
        this.storedFrameHeight = Math.max(1, height / downscaleFactor);
        this.targetFps = this.fpsCap;
        this.bufferDurationMs = durationSeconds * 1000L;

        // Calculate per-frame cost and max frame count
        long perFrameBytes = (long) storedFrameWidth * storedFrameHeight * 3;
        int totalFrames = this.fpsCap * durationSeconds;
        long totalNeeded = perFrameBytes * totalFrames;

        // Cap frame count so it fits within the disk budget
        if (perFrameBytes > 0) {
            int maxByBudget = (int) (diskBudgetBytes / perFrameBytes);
            this.maxFrameCount = Math.min(totalFrames, Math.max(1, maxByBudget));
        } else {
            this.maxFrameCount = totalFrames;
        }

        synchronized (lock) {
            // Fresh buffer directory on disk.
            resetStorageLocked();
            try {
                this.bufferDir = config.getOutputDirectory().resolve(".replay_buffer");
                // Clean any stale buffer left over from a previous session/crash.
                deleteDirectoryContents(this.bufferDir);
                Files.createDirectories(this.bufferDir);
                openNewChunkLocked();
            } catch (Exception e) {
                RecordableMod.LOGGER.warn("Failed to prepare disk replay buffer directory.", e);
            }
            active.set(true);
        }

        RecordableMod.LOGGER.info("Replay buffer started (disk-backed): {}x{} (stored {}x{}, downscale={}x) @{} FPS, {}s, "
                + "diskBudget={}MB, maxFrames={}, perFrame={}KB, totalNeeded={}MB, dir={}",
                width, height, storedFrameWidth, storedFrameHeight, downscaleFactor,
                fps, durationSeconds, budgetMB, maxFrameCount,
                perFrameBytes / 1024, totalNeeded / (1024 * 1024), bufferDir);
    }

    /**
     * Stops the replay buffer and releases all buffered frames + disk files.
     */
    public void stop() {
        synchronized (lock) {
            active.set(false);
            if (saving.get()) {
                // A save is reading the files; defer cleanup until it finishes.
                pinnedForSaving = true;
                return;
            }
            resetStorageLocked();
        }
        RecordableMod.LOGGER.info("Replay buffer stopped. Disk buffer released.");
    }

    /** Closes handles, deletes chunk files and clears the index. Caller holds {@link #lock}. */
    private void resetStorageLocked() {
        for (Chunk c : chunks.values()) {
            closeChunkOutput(c);
            try { Files.deleteIfExists(c.path); } catch (Exception ignored) {}
        }
        chunks.clear();
        index.clear();
        pendingChunkDeletions.clear();
        currentChunk = null;
        nextChunkId = 0;
        currentDiskBytes.set(0);
        if (bufferDir != null) {
            try { Files.deleteIfExists(bufferDir); } catch (Exception ignored) {}
        }
    }

    /** Opens a new active chunk file for appending. Caller holds {@link #lock}. */
    private void openNewChunkLocked() throws Exception {
        int id = nextChunkId++;
        Path path = bufferDir.resolve("chunk-" + id + ".raw");
        Chunk chunk = new Chunk(id, path);
        chunk.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 16);
        chunks.put(id, chunk);
        currentChunk = chunk;
    }

    private static void closeChunkOutput(Chunk c) {
        if (c != null && c.out != null) {
            try { c.out.flush(); } catch (Exception ignored) {}
            try { c.out.close(); } catch (Exception ignored) {}
            c.out = null;
        }
    }

    /**
     * Adds a frame to the disk-backed circular buffer.
     *
     * <p>Frames are downscaled (if the preset/platform requires it), appended to
     * the current chunk file, and tracked by a lightweight in-memory index. The
     * buffer enforces time-based, disk-budget and frame-count eviction.</p>
     *
     * @param rgbPixels raw RGB pixel data (full resolution from capture)
     */
    public void addFrame(byte[] rgbPixels) {
        if (!active.get() || rgbPixels == null) return;

        long now = System.currentTimeMillis();

        // Honor the replay-quality fps cap: drop frames that arrive faster than the target
        // interval so lower presets actually reduce captured frames (and disk). A small 1ms
        // tolerance avoids rejecting frames that are only marginally early.
        if (frameIntervalMs > 0 && lastAcceptedFrameMs != 0L
                && (now - lastAcceptedFrameMs) < (frameIntervalMs - 1)) {
            return;
        }
        lastAcceptedFrameMs = now;

        // Downscale if needed (mobile devices / quality presets)
        byte[] storedPixels;
        if (downscaleFactor > 1) {
            storedPixels = downscaleRgb(rgbPixels, frameWidth, frameHeight, downscaleFactor);
        } else {
            storedPixels = rgbPixels;
        }

        synchronized (lock) {
            if (!active.get() || currentChunk == null || currentChunk.out == null) return;
            try {
                long offset = currentChunk.bytesWritten;
                currentChunk.out.write(storedPixels, 0, storedPixels.length);
                currentChunk.bytesWritten += storedPixels.length;
                currentChunk.refCount++;

                index.addLast(new FrameRef(currentChunk.id, offset, storedPixels.length,
                        storedFrameWidth, storedFrameHeight, now));
                currentDiskBytes.addAndGet(storedPixels.length);

                // Roll to a new chunk once the current one is big enough.
                if (currentChunk.bytesWritten >= CHUNK_MAX_BYTES) {
                    closeChunkOutput(currentChunk);
                    openNewChunkLocked();
                }

                // Trim by time
                long cutoff = now - bufferDurationMs;
                while (!index.isEmpty()) {
                    FrameRef oldest = index.peekFirst();
                    if (oldest != null && oldest.timestampMs < cutoff) {
                        evictOldestLocked();
                    } else {
                        break;
                    }
                }
                // Trim by disk budget
                while (currentDiskBytes.get() > diskBudgetBytes && !index.isEmpty()) {
                    evictOldestLocked();
                }
                // Trim by max frame count
                while (index.size() > maxFrameCount && !index.isEmpty()) {
                    evictOldestLocked();
                }
            } catch (Exception e) {
                RecordableMod.LOGGER.warn("Failed to append replay frame to disk; stopping buffer.", e);
                active.set(false);
            }
        }
    }

    /** Removes the oldest frame and frees its chunk once fully drained. Caller holds {@link #lock}. */
    private void evictOldestLocked() {
        FrameRef removed = index.pollFirst();
        if (removed == null) return;
        currentDiskBytes.addAndGet(-removed.length);
        Chunk chunk = chunks.get(removed.chunkId);
        if (chunk != null) {
            chunk.refCount--;
            if (chunk.refCount <= 0 && chunk != currentChunk) {
                if (pinnedForSaving) {
                    // A save is reading files; delete after it completes.
                    pendingChunkDeletions.addLast(chunk.id);
                } else {
                    removeChunkLocked(chunk);
                }
            }
        }
    }

    private void removeChunkLocked(Chunk chunk) {
        closeChunkOutput(chunk);
        try { Files.deleteIfExists(chunk.path); } catch (Exception ignored) {}
        chunks.remove(chunk.id);
    }

    /**
     * Downscales RGB24 pixel data by a given factor using simple area averaging.
     */
    private static byte[] downscaleRgb(byte[] src, int srcWidth, int srcHeight, int factor) {
        int dstWidth = srcWidth / factor;
        int dstHeight = srcHeight / factor;
        byte[] dst = new byte[dstWidth * dstHeight * 3];

        for (int dy = 0; dy < dstHeight; dy++) {
            for (int dx = 0; dx < dstWidth; dx++) {
                int r = 0, g = 0, b = 0;
                int count = 0;
                for (int fy = 0; fy < factor; fy++) {
                    for (int fx = 0; fx < factor; fx++) {
                        int sx = dx * factor + fx;
                        int sy = dy * factor + fy;
                        int srcIdx = (sy * srcWidth + sx) * 3;
                        if (srcIdx + 2 < src.length) {
                            r += src[srcIdx] & 0xFF;
                            g += src[srcIdx + 1] & 0xFF;
                            b += src[srcIdx + 2] & 0xFF;
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    int dstIdx = (dy * dstWidth + dx) * 3;
                    dst[dstIdx] = (byte) (r / count);
                    dst[dstIdx + 1] = (byte) (g / count);
                    dst[dstIdx + 2] = (byte) (b / count);
                }
            }
        }
        return dst;
    }

    /**
     * Saves the current buffer contents to a video file using FFmpeg.
     *
     * @param client the Minecraft client for messaging
     */
    public void saveBuffer(MinecraftClient client) {
        if (!active.get()) {
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§cReplay buffer is not active.", true);
            return;
        }

        if (saving.getAndSet(true)) {
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§eReplay buffer is already being saved...", true);
            return;
        }

        final FrameRef[] frames;
        final long diskBytes;
        synchronized (lock) {
            if (index.isEmpty()) {
                saving.set(false);
                RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§eReplay buffer is empty. Play for a few seconds first.", true);
                return;
            }
            // Flush the active chunk so all buffered bytes are readable from disk.
            if (currentChunk != null && currentChunk.out != null) {
                try { currentChunk.out.flush(); } catch (Exception ignored) {}
            }
            frames = index.toArray(new FrameRef[0]);
            diskBytes = currentDiskBytes.get();
            // Pin chunk files so eviction/stop does not delete anything mid-read.
            pinnedForSaving = true;
        }

        if (frames.length < 2) {
            finishSaving();
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§eNot enough frames in replay buffer.", true);
            return;
        }

        RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§eSaving replay buffer (" + frames.length + " frames, "
                + (diskBytes / (1024 * 1024)) + " MB on disk)...", true);

        Thread saveThread = new Thread(() -> {
            try {
                saveFramesToFile(client, frames);
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("Failed to save replay buffer.", t);
                RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§cFailed to save replay buffer: " + t.getMessage(), false);
            } finally {
                finishSaving();
            }
        }, "Record-able Replay Save");
        saveThread.setDaemon(true);
        saveThread.start();
    }

    /** Clears the saving flag and applies any deletions/cleanup deferred during the save. */
    private void finishSaving() {
        synchronized (lock) {
            saving.set(false);
            pinnedForSaving = false;
            // Apply chunk deletions that were deferred while pinned.
            while (!pendingChunkDeletions.isEmpty()) {
                Integer id = pendingChunkDeletions.pollFirst();
                Chunk chunk = id != null ? chunks.get(id) : null;
                if (chunk != null && chunk.refCount <= 0 && chunk != currentChunk) {
                    removeChunkLocked(chunk);
                }
            }
            // If the buffer was stopped during the save, finish the teardown now.
            if (!active.get()) {
                resetStorageLocked();
            }
        }
    }

    private void saveFramesToFile(MinecraftClient client, FrameRef[] frames) throws Exception {
        RecordableConfig config = RecordableConfig.get();
        Path outputDir = config.getOutputDirectory();
        Files.createDirectories(outputDir);

        String ext = config.getFormat();
        String base = RecordableConfig.resolveFilenamePattern(RecordableConfig.defaultReplayFilenamePattern());
        Path outputFile = outputDir.resolve(base + "." + ext);
        int dupIndex = 1;
        while (Files.exists(outputFile)) {
            outputFile = outputDir.resolve(base + "-" + dupIndex + "." + ext);
            dupIndex++;
        }

        FFmpegEncoder.FfmpegStatus ffStatus = FFmpegEncoder.detectFfmpeg();
        if (!ffStatus.found()) {
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§cFFmpeg not found. Cannot save replay.", false);
            return;
        }

        // Use the stored frame dimensions (may be downscaled)
        int outWidth = frames[0].width;
        int outHeight = frames[0].height;

        // Calculate effective FPS from frame timestamps
        long durationMs = frames[frames.length - 1].timestampMs - frames[0].timestampMs;
        double effectiveFps = durationMs > 0 ? (frames.length * 1000.0 / durationMs) : targetFps;
        int fps = Math.max(1, Math.min(120, (int) Math.round(effectiveFps)));

        // Build FFmpeg command for raw video input
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                ffStatus.executable(),
                "-y",
                "-f", "rawvideo",
                "-pixel_format", "rgb24",
                "-video_size", outWidth + "x" + outHeight,
                "-framerate", String.valueOf(fps),
                "-i", "pipe:0"));
        // V1-0.08: apply Lunar-style smooth motion to saved replay clips too.
        String smoothFilter = SmoothMotion.buildFilter(config, fps);
        if (smoothFilter != null) {
            cmd.add("-vf");
            cmd.add(smoothFilter);
        }
        cmd.add("-c:v"); cmd.add("libx264");
        cmd.add("-preset"); cmd.add("fast");
        cmd.add("-crf"); cmd.add("23");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add(outputFile.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        // Drain FFmpeg's merged stdout/stderr on a separate thread. If this pipe is never
        // read, FFmpeg blocks once the OS pipe buffer (~64KB) fills, which in turn blocks the
        // frame writes below and deadlocks the save until the JVM exits (same failure mode
        // documented in KillClipBuffer.encode()).
        final StringBuilder ffmpegLog = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (ffmpegLog.length() < 8000) {
                        ffmpegLog.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
        }, "Record-able Replay Save FFmpeg Log");
        drain.setDaemon(true);
        drain.start();

        // Stream frames from disk to FFmpeg's stdin, reusing a handle per chunk.
        Map<Integer, RandomAccessFile> openChunks = new HashMap<>();
        try (OutputStream stdin = process.getOutputStream()) {
            byte[] frameBuf = null;
            for (FrameRef frame : frames) {
                RandomAccessFile raf = openChunks.get(frame.chunkId);
                if (raf == null) {
                    Path chunkPath;
                    synchronized (lock) {
                        Chunk chunk = chunks.get(frame.chunkId);
                        chunkPath = chunk != null ? chunk.path : null;
                    }
                    if (chunkPath == null || !Files.exists(chunkPath)) {
                        continue; // chunk gone (evicted); skip this frame
                    }
                    raf = new RandomAccessFile(chunkPath.toFile(), "r");
                    openChunks.put(frame.chunkId, raf);
                }
                if (frameBuf == null || frameBuf.length != frame.length) {
                    frameBuf = new byte[frame.length];
                }
                raf.seek(frame.offset);
                raf.readFully(frameBuf);
                stdin.write(frameBuf);
            }
            stdin.flush();
        } finally {
            for (RandomAccessFile raf : openChunks.values()) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }

        int exitCode = process.waitFor();
        try {
            drain.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (exitCode == 0 && Files.exists(outputFile)) {
            long fileSize = Files.size(outputFile);
            String msg = "§a✓ Replay saved: " + outputFile.getFileName()
                    + " (" + RecordingManager.formatBytes(fileSize)
                    + ", " + String.format("%.1fs", durationMs / 1000.0) + ")";
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, msg, false);
            RecordableMod.LOGGER.info("Replay buffer saved: {} ({} frames, {} at {}x{})",
                    outputFile, frames.length, RecordingManager.formatBytes(fileSize), outWidth, outHeight);
        } else {
            RecordableMod.LOGGER.warn("Replay buffer save failed (exit code {}): {}", exitCode, ffmpegLog.toString().trim());
            RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "§cReplay save failed (exit code " + exitCode + ").", false);
        }
    }

    public boolean isActive() { return active.get(); }
    public boolean isSaving() { return saving.get(); }
    public int getBufferedFrameCount() { synchronized (lock) { return index.size(); } }
    public long getCurrentMemoryMB() { return currentDiskBytes.get() / (1024L * 1024L); }
    public long getMemoryBudgetMB() { return diskBudgetBytes / (1024L * 1024L); }
    /** Alias with disk-oriented naming. */
    public long getCurrentDiskMB() { return currentDiskBytes.get() / (1024L * 1024L); }
    /** Alias with disk-oriented naming. */
    public long getDiskBudgetMB() { return diskBudgetBytes / (1024L * 1024L); }
    public int getMaxFrameCount() { return maxFrameCount; }
    public int getDownscaleFactor() { return downscaleFactor; }

    public int getBufferedSeconds() {
        synchronized (lock) {
            if (index.isEmpty()) return 0;
            FrameRef first = index.peekFirst();
            FrameRef last = index.peekLast();
            if (first == null || last == null) return 0;
            return (int) ((last.timestampMs - first.timestampMs) / 1000L);
        }
    }

    /**
     * Checks if there is sufficient free disk space to start the replay buffer.
     *
     * @param width  capture width
     * @param height capture height
     * @param fps    target FPS
     * @param durationSeconds buffer duration
     * @return {@code null} if safe to start, or a warning message if disk is too low
     */
    public static String checkMemorySafety(int width, int height, int fps, int durationSeconds) {
        int downscale = PlatformUtils.getReplayBufferDownscaleFactor();
        int sw = width / downscale;
        int sh = height / downscale;
        long perFrame = (long) sw * sh * 3;
        long totalFrames = (long) fps * durationSeconds;
        long totalNeeded = perFrame * totalFrames;

        long budgetBytes = PlatformUtils.getReplayBufferDiskBudgetMB() * 1024L * 1024L;
        long needed = Math.min(totalNeeded, budgetBytes);

        long freeBytes = PlatformUtils.getFreeDiskSpaceBytes(RecordableConfig.get().getOutputDirectory());
        if (freeBytes < 0) {
            return null; // can't determine free space; allow and let eviction cap usage
        }
        long minHeadroom = 256L * 1024L * 1024L; // keep 256 MB free on the volume
        if (freeBytes < needed + minHeadroom) {
            long freeMB = freeBytes / (1024L * 1024L);
            long neededMB = needed / (1024L * 1024L);
            return String.format("§cLow disk space! Only %d MB free, need ~%d MB for buffer + %d MB headroom.",
                    freeMB, neededMB, minHeadroom / (1024 * 1024));
        }
        return null; // safe
    }

    /**
     * Returns a human-readable estimate of replay buffer disk usage for the settings screen.
     */
    public static String getMemoryEstimate(int width, int height, int fps, int durationSeconds) {
        int downscale = PlatformUtils.getReplayBufferDownscaleFactor();
        int sw = width / downscale;
        int sh = height / downscale;
        long perFrame = (long) sw * sh * 3;
        long totalFrames = (long) fps * durationSeconds;
        long totalMB = (perFrame * totalFrames) / (1024L * 1024L);
        long budgetMB = PlatformUtils.getReplayBufferDiskBudgetMB();
        long effectiveMB = Math.min(totalMB, budgetMB);

        String suffix = downscale > 1 ? " (mobile: " + sw + "x" + sh + ")" : "";
        return String.format("~%d MB disk / %d MB budget%s", effectiveMB, budgetMB, suffix);
    }

    /** Recursively deletes the contents of a directory (and the directory itself). */
    private static void deleteDirectoryContents(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
