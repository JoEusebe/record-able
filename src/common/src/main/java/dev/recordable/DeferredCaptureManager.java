package dev.recordable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages deferred (offline) render mode: stores captured frames to disk during
 * recording, tracks metadata, and provides access for post-processing rendering.
 *
 * <p><b>Directory structure:</b>
 * <pre>
 * .minecraft/recordable/deferred/
 *   {uuid}/
 *     metadata.json (DeferredRecordingMetadata)
 *     frames/
 *       f00001.rgb
 *       f00002.rgb
 *       ...
 *     audio.wav (game audio, if enabled)
 * </pre>
 * </p>
 */
public final class DeferredCaptureManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile DeferredCaptureManager instance;

    private final Path deferredRoot;
    private String activeSessionId;
    private DeferredRecordingMetadata activeMetadata;
    private int frameIndex;
    // Single-threaded background writer: render thread submits frames here and
    // returns immediately, keeping disk I/O off the render thread entirely.
    private ExecutorService frameWriteExecutor;

    private DeferredCaptureManager() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        this.deferredRoot = gameDir.resolve("recordable").resolve("deferred");
        try {
            Files.createDirectories(deferredRoot);
        } catch (IOException e) {
            RecordableMod.LOGGER.error("Failed to create deferred render directory: {}", deferredRoot, e);
        }
    }

    public static DeferredCaptureManager getInstance() {
        DeferredCaptureManager inst = instance;
        if (inst == null) {
            synchronized (DeferredCaptureManager.class) {
                inst = instance;
                if (inst == null) {
                    inst = new DeferredCaptureManager();
                    instance = inst;
                }
            }
        }
        return inst;
    }

    /**
     * Starts a new deferred capture session.
     *
     * @param originalName Original recording filename (e.g., "recordable-20260720-105338")
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param captureFps Capture frame rate
     * @param targetFps Desired output frame rate
     * @param interpolation Interpolation method ("none", "duplicate", "motion")
     * @param audioEnabled Whether audio is being captured
     * @return Session ID (UUID)
     */
    public String startSession(String originalName, int width, int height, int captureFps,
                               int targetFps, String interpolation, boolean audioEnabled) {
        if (activeSessionId != null) {
            // A previous session was never finalized/cancelled: discard it now so its
            // executor thread and queued writes don't leak while we overwrite the state.
            RecordableMod.LOGGER.warn("[DeferredCapture] startSession called while session {} was still active; cancelling it.",
                    activeSessionId);
            cancelSession();
        }

        activeSessionId = UUID.randomUUID().toString();
        activeMetadata = new DeferredRecordingMetadata();
        activeMetadata.sessionId = activeSessionId;
        activeMetadata.originalName = originalName;
        activeMetadata.width = width;
        activeMetadata.height = height;
        activeMetadata.captureFps = captureFps;
        activeMetadata.targetFps = targetFps;
        activeMetadata.interpolation = interpolation;
        activeMetadata.audioEnabled = audioEnabled;
        activeMetadata.startTimeMs = System.currentTimeMillis();
        activeMetadata.status = "capturing";
        frameIndex = 0;

        // Start the background frame-write thread for this session.
        frameWriteExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "recordable-deferred-write");
            t.setDaemon(true);
            return t;
        });

        Path sessionDir = getSessionDir(activeSessionId);
        Path framesDir = sessionDir.resolve("frames");
        try {
            Files.createDirectories(framesDir);
            saveMetadata(activeSessionId, activeMetadata);
            RecordableMod.LOGGER.info("[DeferredCapture] Started session {} for '{}'", activeSessionId, originalName);
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[DeferredCapture] Failed to create session directory: {}", sessionDir, e);
        }

        return activeSessionId;
    }

    /**
     * Sets the output subfolder (relative to the output directory) for the active session and
     * persists it. Used to route auto-clips into {@code clips/<event>/} while keeping manual
     * recordings in the output root.
     *
     * @param subfolder relative subfolder such as "clips/kills", or null/empty for the root
     */
    public void setActiveOutputSubfolder(String subfolder) {
        if (activeSessionId == null || activeMetadata == null) {
            return;
        }
        activeMetadata.outputSubfolder = subfolder;
        try {
            saveMetadata(activeSessionId, activeMetadata);
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[DeferredCapture] Failed to save output subfolder: {}", e.getMessage());
        }
    }

    /**
     * Stores a captured frame to disk (raw RGB24 format).
     *
     * <p>The write is submitted to a background thread so the render thread is
     * never blocked by disk I/O. The caller must not modify {@code rgbData} after
     * this call; ownership is transferred to the write task, which releases the
     * buffer back to {@link FrameBufferPool} when done.</p>
     *
     * @param rgbData Raw RGB pixel data (width * height * 3 bytes)
     * @param timestampMs Frame timestamp in milliseconds (relative to recording start)
     */
    public void storeFrame(byte[] rgbData, long timestampMs) {
        if (activeSessionId == null || rgbData == null || frameWriteExecutor == null) {
            return;
        }

        frameIndex++;
        Path framePath = getSessionDir(activeSessionId)
                .resolve("frames")
                .resolve(String.format("f%05d.rgb", frameIndex));
        activeMetadata.frameTimestamps.add(timestampMs);

        // Capture local references for the lambda (session fields must not be read
        // on the background thread since they may change when the session ends).
        final Path writePath = framePath;
        final byte[] data = rgbData;
        frameWriteExecutor.execute(() -> {
            try {
                Files.write(writePath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                RecordableMod.LOGGER.warn("[DeferredCapture] Failed to write frame: {}", e.getMessage());
            } finally {
                FrameBufferPool.getInstance().release(data);
            }
        });
    }

    /**
     * Stores the audio WAV file for the current session.
     *
     * @param audioWavPath Path to the audio WAV file
     */
    public void storeAudio(Path audioWavPath) {
        if (activeSessionId == null || audioWavPath == null || !Files.exists(audioWavPath)) {
            return;
        }

        Path targetPath = getSessionDir(activeSessionId).resolve("audio.wav");
        try {
            Files.copy(audioWavPath, targetPath);
            RecordableMod.LOGGER.info("[DeferredCapture] Stored audio: {}", targetPath);
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[DeferredCapture] Failed to copy audio: {}", e.getMessage());
        }
    }

    /**
     * Finalizes the current capture session (marks as ready to render).
     *
     * <p>Drains all pending background frame writes before writing metadata so
     * the on-disk session is always in a consistent state.</p>
     */
    public void finalizeSession() {
        if (activeSessionId == null) {
            return;
        }

        // Wait for all queued frame writes to finish before saving metadata.
        if (frameWriteExecutor != null) {
            frameWriteExecutor.shutdown();
            try {
                if (!frameWriteExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    RecordableMod.LOGGER.warn("[DeferredCapture] Frame write executor timed out; some frames may be missing.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RecordableMod.LOGGER.warn("[DeferredCapture] Interrupted while waiting for frame writes.");
            }
            frameWriteExecutor = null;
        }

        activeMetadata.status = "pending";
        activeMetadata.endTimeMs = System.currentTimeMillis();
        activeMetadata.totalFrames = frameIndex;
        activeMetadata.durationMs = activeMetadata.endTimeMs - activeMetadata.startTimeMs;

        try {
            saveMetadata(activeSessionId, activeMetadata);
            long sizeBytes = calculateSessionSize(activeSessionId);
            RecordableMod.LOGGER.info("[DeferredCapture] Finalized session {}: {} frames, {} MB",
                    activeSessionId, frameIndex, sizeBytes / (1024 * 1024));
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[DeferredCapture] Failed to save metadata: {}", e.getMessage());
        }

        activeSessionId = null;
        activeMetadata = null;
        frameIndex = 0;
    }

    /**
     * Cancels the current capture session and deletes all temp frames.
     */
    public void cancelSession() {
        if (activeSessionId == null) {
            return;
        }

        // Discard any pending writes immediately.
        if (frameWriteExecutor != null) {
            frameWriteExecutor.shutdownNow();
            frameWriteExecutor = null;
        }

        String sessionId = activeSessionId;
        activeSessionId = null;
        activeMetadata = null;
        frameIndex = 0;

        deleteSession(sessionId);
        RecordableMod.LOGGER.info("[DeferredCapture] Cancelled and deleted session {}", sessionId);
    }

    /**
     * Returns a list of all pending (unrendered) sessions.
     */
    public List<DeferredRecordingMetadata> getPendingSessions() {
        List<DeferredRecordingMetadata> pending = new ArrayList<>();
        try (Stream<Path> stream = Files.list(deferredRoot)) {
            List<Path> sessionDirs = stream.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path sessionDir : sessionDirs) {
                Path metadataPath = sessionDir.resolve("metadata.json");
                if (Files.exists(metadataPath)) {
                    try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
                        DeferredRecordingMetadata meta = GSON.fromJson(reader, DeferredRecordingMetadata.class);
                        if ("pending".equals(meta.status)) {
                            pending.add(meta);
                        }
                    }
                }
            }
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[DeferredCapture] Failed to list pending sessions: {}", e.getMessage());
        }
        return pending;
    }

    /**
     * Loads metadata for a specific session.
     */
    public DeferredRecordingMetadata getMetadata(String sessionId) {
        Path metadataPath = getSessionDir(sessionId).resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, DeferredRecordingMetadata.class);
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[DeferredCapture] Failed to load metadata for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes a session and all its frames/audio.
     */
    public void deleteSession(String sessionId) {
        Path sessionDir = getSessionDir(sessionId);
        if (!Files.exists(sessionDir)) {
            return;
        }

        try {
            deleteRecursive(sessionDir);
            RecordableMod.LOGGER.info("[DeferredCapture] Deleted session {}", sessionId);
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[DeferredCapture] Failed to delete session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Marks a session as rendering (status = "rendering").
     */
    public void markRendering(String sessionId) {
        DeferredRecordingMetadata meta = getMetadata(sessionId);
        if (meta != null) {
            meta.status = "rendering";
            try {
                saveMetadata(sessionId, meta);
            } catch (IOException e) {
                RecordableMod.LOGGER.warn("[DeferredCapture] Failed to mark session as rendering: {}", e.getMessage());
            }
        }
    }

    /**
     * Marks a session as completed (status = "completed").
     */
    public void markCompleted(String sessionId) {
        DeferredRecordingMetadata meta = getMetadata(sessionId);
        if (meta != null) {
            meta.status = "completed";
            try {
                saveMetadata(sessionId, meta);
            } catch (IOException e) {
                RecordableMod.LOGGER.warn("[DeferredCapture] Failed to mark session as completed: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns the directory for a specific session.
     */
    public Path getSessionDir(String sessionId) {
        return deferredRoot.resolve(sessionId);
    }

    /**
     * Returns the frames directory for a specific session.
     */
    public Path getFramesDir(String sessionId) {
        return getSessionDir(sessionId).resolve("frames");
    }

    /**
     * Returns the audio WAV path for a specific session (if it exists).
     */
    public Path getAudioPath(String sessionId) {
        Path audioPath = getSessionDir(sessionId).resolve("audio.wav");
        return Files.exists(audioPath) ? audioPath : null;
    }

    /**
     * Calculates the total size of a session (frames + audio + metadata) in bytes.
     */
    public long calculateSessionSize(String sessionId) {
        Path sessionDir = getSessionDir(sessionId);
        if (!Files.exists(sessionDir)) {
            return 0L;
        }

        try (Stream<Path> stream = Files.walk(sessionDir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void saveMetadata(String sessionId, DeferredRecordingMetadata meta) throws IOException {
        Path metadataPath = getSessionDir(sessionId).resolve("metadata.json");
        try (Writer writer = Files.newBufferedWriter(metadataPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(meta, writer);
        }
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                for (Path child : stream.collect(Collectors.toList())) {
                    deleteRecursive(child);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * Metadata for a deferred recording session.
     */
    public static class DeferredRecordingMetadata {
        public String sessionId;
        public String originalName;
        public int width;
        public int height;
        public int captureFps;
        public int targetFps;
        public String interpolation;
        public boolean audioEnabled;
        public long startTimeMs;
        public long endTimeMs;
        public long durationMs;
        public int totalFrames;
        public String status; // "capturing", "pending", "rendering", "completed"
        /**
         * Output subfolder (relative to the output directory) the finished video should be
         * written into, e.g. "clips/kills" for an auto-clip. Null or empty means the output
         * directory root (manual recordings).
         */
        public String outputSubfolder;
        public List<Long> frameTimestamps = new ArrayList<>(); // timestamp for each frame
    }
}
