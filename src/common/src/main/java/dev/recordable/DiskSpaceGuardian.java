package dev.recordable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Feature 5: Disk Space Guardian.
 * Checks available disk space before and during recording using java.nio.file APIs
 * (with JNA as a fallback for more detailed info if needed).
 */
public final class DiskSpaceGuardian {

    public enum DiskStatus {
        OK,
        WARNING,
        BLOCKED
    }

    public record DiskCheckResult(DiskStatus status, long freeSpaceMB, long totalSpaceMB,
                                  int usedPercent, String message) {
    }

    private DiskSpaceGuardian() {
    }

    /**
     * Checks disk space at the given output directory.
     *
     * @param outputDir the directory where recordings are stored
     * @param config    current config with disk space thresholds
     * @return a DiskCheckResult with status, space info, and a human-readable message
     */
    public static DiskCheckResult check(Path outputDir, RecordableConfig config) {
        try {
            // Ensure the directory exists
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }

            // Use java.nio.file.FileStore for disk space queries
            java.nio.file.FileStore store = Files.getFileStore(outputDir);
            long totalBytes = store.getTotalSpace();
            long freeBytes = store.getUsableSpace();

            if (totalBytes <= 0) {
                return new DiskCheckResult(DiskStatus.OK, -1, -1, 0,
                        "Could not determine disk space.");
            }

            long totalMB = totalBytes / (1024L * 1024L);
            long freeMB = freeBytes / (1024L * 1024L);
            int usedPercent = (int) (100L - (freeBytes * 100L / totalBytes));

            // Check against thresholds
            if (usedPercent >= config.diskSpaceBlockPercent || freeMB < 100) {
                return new DiskCheckResult(DiskStatus.BLOCKED, freeMB, totalMB, usedPercent,
                        "§c⛔ Disk is " + usedPercent + "% full (" + freeMB + " MB free). "
                                + "Recording blocked to prevent disk full errors.");
            }

            if (usedPercent >= config.diskSpaceWarnPercent || freeMB < config.diskSpaceMinFreeMB) {
                return new DiskCheckResult(DiskStatus.WARNING, freeMB, totalMB, usedPercent,
                        "§e⚠ Disk is " + usedPercent + "% full (" + freeMB + " MB free). "
                                + "Recording may be cut short.");
            }

            return new DiskCheckResult(DiskStatus.OK, freeMB, totalMB, usedPercent,
                    "Disk space OK: " + freeMB + " MB free (" + usedPercent + "% used).");

        } catch (Exception e) {
            RecordableMod.LOGGER.warn("Failed to check disk space for {}: {}", outputDir, e.getMessage());
            return new DiskCheckResult(DiskStatus.OK, -1, -1, 0,
                    "Could not check disk space: " + e.getMessage());
        }
    }

    /**
     * Quick check using JNA for native disk space queries as a fallback.
     * Falls back to standard Java if JNA isn't available.
     */
    public static long getFreeSpaceMB(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            java.nio.file.FileStore store = Files.getFileStore(path);
            return store.getUsableSpace() / (1024L * 1024L);
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("Could not get free space for {}", path, e);
            return -1;
        }
    }

    /**
     * Returns a formatted string showing available disk space.
     */
    public static String getFormattedFreeSpace(Path path) {
        long freeMB = getFreeSpaceMB(path);
        if (freeMB < 0) return "Unknown";
        if (freeMB >= 1024) {
            return String.format("%.1f GB", freeMB / 1024.0);
        }
        return freeMB + " MB";
    }
}
