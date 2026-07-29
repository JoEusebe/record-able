package dev.recordable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * V1-0.06 Feature 6: Storage Manager.
 *
 * <p>Pure-Java backend (shared across all variants) that powers the storage dashboard:
 * scanning the recordings directory, computing usage statistics, manual cleanup,
 * opt-in auto-cleanup (default OFF), protected-file flags, and ffmpeg-based
 * recompression to reclaim space.</p>
 */
public final class StorageManager {

    /** Recognized recording container extensions. */
    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi", ".gif"};

    private StorageManager() {}

    /** A single recording file with lightweight (non-probing) info. */
    public record StoredFile(Path path, String filename, long sizeBytes, long modifiedMillis, boolean protectedFlag) {
        public String sizeDisplay() {
            return humanReadable(sizeBytes);
        }
    }

    /** Aggregate dashboard statistics for the recordings folder + disk. */
    public record StorageStats(long recordingsBytes, int recordingCount,
                               long diskFreeBytes, long diskTotalBytes, int diskUsedPercent) {
        public String recordingsDisplay() { return humanReadable(recordingsBytes); }
        public String diskFreeDisplay() { return humanReadable(diskFreeBytes); }
        public String diskTotalDisplay() { return humanReadable(diskTotalBytes); }
    }

    /** Outcome of a cleanup operation. */
    public record CleanupResult(int filesDeleted, long bytesFreed, List<String> deletedNames) {
        public String bytesFreedDisplay() { return humanReadable(bytesFreed); }
    }

    /** Returns true if the file name looks like a recording video. */
    public static boolean isVideoFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** List all recordings in the output directory (most-recent first), tagging protected files. */
    public static List<StoredFile> listRecordings(RecordableConfig config) {
        List<StoredFile> out = new ArrayList<>();
        Path dir = config.getOutputDirectory();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isVideoFile(p.getFileName().toString()))
                  .forEach(p -> {
                      long size = safeSize(p);
                      long mod = safeModified(p);
                      boolean prot = config.storageProtectedFiles != null
                              && config.storageProtectedFiles.contains(p.getFileName().toString());
                      out.add(new StoredFile(p, p.getFileName().toString(), size, mod, prot));
                  });
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("StorageManager: failed to list {}: {}", dir, e.getMessage());
        }
        out.sort(Comparator.comparingLong((StoredFile file) -> file.modifiedMillis()).reversed());
        return out;
    }

    /** Compute dashboard statistics. */
    public static StorageStats computeStats(RecordableConfig config) {
        List<StoredFile> files = listRecordings(config);
        long total = 0;
        for (StoredFile f : files) total += f.sizeBytes();

        long free = -1, diskTotal = -1;
        int usedPct = 0;
        try {
            Path dir = config.getOutputDirectory();
            if (dir != null) {
                if (!Files.exists(dir)) Files.createDirectories(dir);
                java.nio.file.FileStore store = Files.getFileStore(dir);
                diskTotal = store.getTotalSpace();
                free = store.getUsableSpace();
                if (diskTotal > 0) usedPct = (int) (100L - (free * 100L / diskTotal));
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("StorageManager: disk stat failed: {}", e.getMessage());
        }
        return new StorageStats(total, files.size(), free, diskTotal, usedPct);
    }

    /** Toggle a file's protected flag (protected files are never auto-deleted). */
    public static void toggleProtected(RecordableConfig config, String filename) {
        if (config.storageProtectedFiles == null) {
            config.storageProtectedFiles = new ArrayList<>();
        }
        if (config.storageProtectedFiles.contains(filename)) {
            config.storageProtectedFiles.remove(filename);
        } else {
            config.storageProtectedFiles.add(filename);
        }
        config.save();
    }

    public static boolean isProtected(RecordableConfig config, String filename) {
        return config.storageProtectedFiles != null && config.storageProtectedFiles.contains(filename);
    }

    /** Manually delete a single recording (and its sidecar files), respecting protection. */
    public static boolean deleteRecording(RecordableConfig config, Path file) {
        String name = file.getFileName().toString();
        if (isProtected(config, name)) {
            RecordableMod.LOGGER.info("StorageManager: refusing to delete protected file {}", name);
            return false;
        }
        boolean ok = deleteWithSidecars(file);
        if (ok) VideoMetadata.clearCache();
        return ok;
    }

    /**
     * Run cleanup according to config rules. Honors protected files and the
     * {@code autoCleanupEnabled}/{@code autoCleanupOlderThanDays}/{@code autoCleanupMaxTotalMB} settings.
     * Pass {@code force=true} for a manual "clean now" action even when auto-cleanup is off.
     */
    public static CleanupResult runCleanup(RecordableConfig config, boolean force) {
        List<String> deleted = new ArrayList<>();
        long freed = 0;
        if (!force && !config.autoCleanupEnabled) {
            return new CleanupResult(0, 0, deleted);
        }

        List<StoredFile> files = listRecordings(config);
        long now = System.currentTimeMillis();
        long ageCutoffMs = config.autoCleanupOlderThanDays * 24L * 60L * 60L * 1000L;

        // 1) Age-based deletion.
        for (StoredFile f : files) {
            if (f.protectedFlag()) continue;
            if (now - f.modifiedMillis() > ageCutoffMs) {
                if (deleteWithSidecars(f.path())) {
                    deleted.add(f.filename());
                    freed += f.sizeBytes();
                }
            }
        }

        // 2) Total-size cap deletion (oldest first) if a cap is configured.
        if (config.autoCleanupMaxTotalMB > 0) {
            long capBytes = config.autoCleanupMaxTotalMB * 1024L * 1024L;
            List<StoredFile> remaining = listRecordings(config);
            long total = 0;
            for (StoredFile f : remaining) total += f.sizeBytes();
            // Delete oldest unprotected until under cap.
            remaining.sort(Comparator.comparingLong((StoredFile file) -> file.modifiedMillis()));
            for (StoredFile f : remaining) {
                if (total <= capBytes) break;
                if (f.protectedFlag()) continue;
                if (deleteWithSidecars(f.path())) {
                    deleted.add(f.filename());
                    freed += f.sizeBytes();
                    total -= f.sizeBytes();
                }
            }
        }

        if (!deleted.isEmpty()) VideoMetadata.clearCache();
        return new CleanupResult(deleted.size(), freed, deleted);
    }

    /**
     * Recompress a recording with ffmpeg to reclaim space (H.264, configurable CRF).
     * Produces a sibling "*_compressed.mp4". Returns the new path or null on failure.
     * This is a blocking call; callers should run it off the render thread.
     */
    public static Path compressRecording(RecordableConfig config, Path source) {
        if (source == null || !Files.isRegularFile(source)) return null;
        String ffmpeg = detectFfmpeg();
        if (ffmpeg == null || ffmpeg.isBlank()) {
            RecordableMod.LOGGER.warn("StorageManager: ffmpeg unavailable, cannot compress");
            return null;
        }
        String base = source.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        Path out = source.resolveSibling(stem + "_compressed.mp4");
        int crf = Math.max(0, Math.min(51, config.storageCompressionCrf));
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-i"); cmd.add(source.toString());
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-crf"); cmd.add(String.valueOf(crf));
            cmd.add("-preset"); cmd.add("medium");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add(out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Drain output to avoid blocking.
            try (var in = p.getInputStream()) { in.readAllBytes(); }
            int code = p.waitFor();
            if (code == 0 && Files.isRegularFile(out)) {
                VideoMetadata.clearCache();
                return out;
            }
            RecordableMod.LOGGER.warn("StorageManager: compression exited with code {}", code);
            Files.deleteIfExists(out);
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("StorageManager: compression failed: {}", e.getMessage());
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Delete the file plus known sidecar files (.txt markers, thumbnail). */
    private static boolean deleteWithSidecars(Path file) {
        boolean ok;
        try {
            ok = Files.deleteIfExists(file);
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("StorageManager: failed to delete {}: {}", file, e.getMessage());
            return false;
        }
        // Best-effort sidecar cleanup.
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        try { Files.deleteIfExists(file.resolveSibling(stem + "_markers.txt")); } catch (IOException ignored) {}
        try { Files.deleteIfExists(file.resolveSibling(stem + "_chapters.txt")); } catch (IOException ignored) {}
        return ok;
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    private static long safeModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    /** Format a byte count as B/KB/MB/GB. */
    public static String humanReadable(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /**
     * Detects FFmpeg using a priority-based fallback chain:
     * <ol>
     *   <li>Bundled/downloaded FFmpeg (managed by {@link FfmpegBundleManager})</li>
     *   <li>RECORDABLE_FFMPEG_PATH environment variable</li>
     *   <li>System PATH (tries "ffmpeg" directly)</li>
     * </ol>
     */
    private static String detectFfmpeg() {
        String bundled = FfmpegBundleManager.getBundledFfmpegPath();
        if (bundled != null && !bundled.isBlank()) {
            return bundled;
        }
        String env = System.getenv("RECORDABLE_FFMPEG_PATH");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim());
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (proc.waitFor() == 0) {
                return "ffmpeg";
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
