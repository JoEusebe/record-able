package dev.recordable;

/**
 * A single bookmark placed during a recording session.
 * Stores the timestamp (relative to recording start) and a description.
 */
public record RecordingBookmark(long timestampMs, String description) {

    /**
     * Formats the bookmark as a human-readable line for the bookmarks file.
     * Format: [HH:MM:SS] Description
     */
    public String toFileLine() {
        long totalSeconds = timestampMs / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("[%02d:%02d:%02d] %s", hours, minutes, seconds, description);
    }
}
