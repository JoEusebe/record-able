package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks an in-progress recording so it can be recovered after an unclean exit
 * (a crash, a force-kill, or a power loss) where the encoder never finalized the
 * output file.
 *
 * <p>When a recording starts, a small marker file is written under
 * {@code <gameDir>/recordable/.active_recording}. It records the output path and
 * the display name. On a clean stop (or cancel) the marker is deleted. If the
 * marker still exists on the next launch, the previous session ended without
 * finalizing, so the mod offers to recover the leftover file.</p>
 *
 * <p>Recovery is a best-effort remux: FFmpeg is asked to copy the streams of the
 * partial file into a fresh, properly-finalized container. If the partial file is
 * too damaged to remux, the raw file is kept as-is (renamed) so nothing is lost.</p>
 *
 * <p>This class is intentionally free of any Minecraft-version-specific API so it
 * can be shared unchanged by every variant.</p>
 */
public final class RecoveryManager {

    private RecoveryManager() {}

    private static final String DIR_NAME = "recordable";
    private static final String MARKER_NAME = ".active_recording";

    /** Cached pending state captured once at launch (before the marker is cleared). */
    private static volatile boolean pendingChecked;
    private static volatile Path pendingPath;
    private static volatile String pendingName;

    private static Path markerFile() {
        return FabricLoader.getInstance().getGameDir().resolve(DIR_NAME).resolve(MARKER_NAME);
    }

    /**
     * Records that a recording is now active. Writes the marker file with the
     * output path and a friendly display name. Best effort: never throws.
     */
    public static synchronized void markActive(Path outputFile, String displayName) {
        if (outputFile == null) return;
        try {
            Path marker = markerFile();
            Files.createDirectories(marker.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("path=" + outputFile.toAbsolutePath());
            lines.add("name=" + (displayName == null ? outputFile.getFileName().toString() : displayName));
            Files.write(marker, lines, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // Recovery is a convenience; failing to write the marker must never
            // disrupt an actual recording.
        }
    }

    /** Clears the active-recording marker after a clean stop / cancel / shutdown. */
    public static synchronized void clear() {
        try {
            Files.deleteIfExists(markerFile());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reads the marker (once) and caches whether a recoverable recording is
     * pending. Must be called before {@link #clear()} would run for the new
     * session. Returns {@code true} only when the marker exists, references an
     * existing non-empty file.
     */
    public static synchronized boolean checkForPending() {
        if (pendingChecked) {
            return pendingPath != null;
        }
        pendingChecked = true;
        try {
            Path marker = markerFile();
            if (!Files.exists(marker)) {
                return false;
            }
            Path path = null;
            String name = null;
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.startsWith("path=")) {
                    path = Path.of(line.substring("path=".length()).trim());
                } else if (line.startsWith("name=")) {
                    name = line.substring("name=".length()).trim();
                }
            }
            if (path != null && Files.exists(path) && safeSize(path) > 0L) {
                pendingPath = path;
                pendingName = (name == null || name.isBlank()) ? path.getFileName().toString() : name;
                return true;
            }
            // Marker is stale (file gone or empty): drop it silently.
            Files.deleteIfExists(marker);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPending() {
        return pendingChecked && pendingPath != null;
    }

    public static Path getPendingPath() {
        return pendingPath;
    }

    public static String getPendingName() {
        return pendingName;
    }

    /**
     * Attempts to recover the pending partial recording by remuxing it into a
     * fresh container with FFmpeg. On success the partial file is replaced by the
     * recovered file (same name). If the remux fails, the partial file is kept but
     * renamed with a "_partial" suffix so the user still has the raw data.
     *
     * @param ffmpegExecutable resolved FFmpeg binary path, or {@code null} to skip
     *                         the remux and simply keep the raw partial file.
     * @return the path to the recovered (or preserved) file, or {@code null} on failure.
     */
    public static synchronized Path recoverPending(String ffmpegExecutable) {
        Path partial = pendingPath;
        if (partial == null || !Files.exists(partial)) {
            clearPending();
            return null;
        }
        try {
            String fileName = partial.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : ".mp4";
            Path parent = partial.getParent();

            if (ffmpegExecutable != null && !ffmpegExecutable.isBlank()) {
                Path recovered = parent == null
                        ? Path.of(base + "_recovered" + ext)
                        : parent.resolve(base + "_recovered" + ext);
                int n = 1;
                while (Files.exists(recovered)) {
                    String cand = base + "_recovered (" + n++ + ")" + ext;
                    recovered = parent == null ? Path.of(cand) : parent.resolve(cand);
                }
                boolean ok = remux(ffmpegExecutable, partial, recovered);
                if (ok && Files.exists(recovered) && safeSize(recovered) > 0L) {
                    // Replace the partial with the clean recovered file, keeping the
                    // original name so the user sees a normal recording.
                    try {
                        Files.deleteIfExists(partial);
                        Files.move(recovered, partial);
                        clearPending();
                        return partial;
                    } catch (Throwable moveFail) {
                        // Could not overwrite the original: keep the recovered file
                        // under its own name instead.
                        clearPending();
                        return recovered;
                    }
                }
            }

            // Remux unavailable or failed: preserve the raw partial file so nothing
            // is thrown away. Rename it so the user can tell it may be incomplete.
            Path preserved = parent == null
                    ? Path.of(base + "_partial" + ext)
                    : parent.resolve(base + "_partial" + ext);
            int m = 1;
            while (Files.exists(preserved)) {
                String cand = base + "_partial (" + m++ + ")" + ext;
                preserved = parent == null ? Path.of(cand) : parent.resolve(cand);
            }
            try {
                Files.move(partial, preserved);
            } catch (Throwable renameFail) {
                preserved = partial; // keep original name if the move fails
            }
            clearPending();
            return preserved;
        } catch (Throwable t) {
            clearPending();
            return null;
        }
    }

    /** Discards the pending partial recording (deletes the file and the marker). */
    public static synchronized void discardPending() {
        Path partial = pendingPath;
        if (partial != null) {
            try {
                Files.deleteIfExists(partial);
            } catch (Throwable ignored) {
            }
            // Best-effort sidecar cleanup.
            try {
                String fileName = partial.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String base = dot > 0 ? fileName.substring(0, dot) : fileName;
                Path parent = partial.getParent();
                if (parent != null) {
                    Files.deleteIfExists(parent.resolve(base + "_bookmarks.txt"));
                }
            } catch (Throwable ignored) {
            }
        }
        clearPending();
    }

    private static void clearPending() {
        pendingPath = null;
        pendingName = null;
        clear();
    }

    private static boolean remux(String ffmpeg, Path in, Path out) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(in.toAbsolutePath().toString());
            cmd.add("-c");
            cmd.add("copy");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(out.toAbsolutePath().toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Drain output so the process never blocks on a full pipe.
            try (var in2 = proc.getInputStream()) {
                byte[] buf = new byte[8192];
                while (in2.read(buf) != -1) {
                    // discard
                }
            } catch (IOException ignored) {
            }
            boolean finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
