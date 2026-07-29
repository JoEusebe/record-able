package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight metadata model + cached extraction for recorded video files. */
public final class VideoMetadata {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILENAME_TIMESTAMP = Pattern.compile("(\\d{8}-\\d{6})");
    /** Matches "Duration: HH:MM:SS.xx" in ffmpeg -i output */
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    /** Matches the recurring "time=HH:MM:SS.xx" progress token emitted while ffmpeg decodes. */
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "time=\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private static final Object LOCK = new Object();
    private static final Map<Path, CacheEntry> CACHE = new HashMap<>();
    private static volatile ProbeStatus cachedProbeStatus;

    public final Path file;
    public final String filename;
    public final long sizeBytes;
    public final String sizeDisplay;
    public final String durationDisplay;
    public final double durationSeconds;
    public final String recordedAtDisplay;
    public final long modifiedMillis;
    public final Path thumbnailPath;

    private VideoMetadata(Path file,
                          long sizeBytes,
                          String durationDisplay,
                          double durationSeconds,
                          String recordedAtDisplay,
                          long modifiedMillis,
                          Path thumbnailPath) {
        this.file = file;
        this.filename = file == null || file.getFileName() == null ? "?" : file.getFileName().toString();
        this.sizeBytes = sizeBytes;
        this.sizeDisplay = String.format(Locale.ROOT, "%.2f MB", sizeBytes / (1024.0D * 1024.0D));
        this.durationDisplay = durationDisplay;
        this.durationSeconds = durationSeconds;
        this.recordedAtDisplay = recordedAtDisplay;
        this.modifiedMillis = modifiedMillis;
        this.thumbnailPath = thumbnailPath;
    }

    public static VideoMetadata read(Path videoFile) {
        if (videoFile == null) {
            return new VideoMetadata(Path.of("?"), 0L, "?", -1D, "?", 0L, null);
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);
            CacheEntry cached;
            synchronized (LOCK) {
                cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified) {
                    return cached.metadata;
                }
            }

            double durationSeconds = probeDurationSeconds(normalized);
            String durationDisplay = durationSeconds <= 0D ? "?" : formatDuration(durationSeconds);
            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            VideoMetadata metadata = new VideoMetadata(normalized, size, durationDisplay, durationSeconds, recordedAt, modified, thumbnail);
            synchronized (LOCK) {
                CACHE.put(normalized, new CacheEntry(size, modified, metadata));
            }
            return metadata;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to read video metadata for {}", videoFile, throwable);
            return new VideoMetadata(videoFile, 0L, "?", -1D, "?", safeModifiedMillis(videoFile), null);
        }
    }

    /**
     * Quick read that skips ffprobe (duration will show "..."). Use this for
     * initial UI population, then call {@link #probeDurationFor(Path)} from a
     * background thread to fill in durations without blocking the render thread.
     */
    public static VideoMetadata readQuick(Path videoFile) {
        if (videoFile == null) {
            return new VideoMetadata(Path.of("?"), 0L, "?", -1D, "?", 0L, null);
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);

            // Return cached entry if still valid (includes duration from prior probe)
            synchronized (LOCK) {
                CacheEntry cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified) {
                    return cached.metadata;
                }
            }

            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            // No ffprobe call -- duration is pending
            VideoMetadata metadata = new VideoMetadata(normalized, size, "...", -1D, recordedAt, modified, thumbnail);
            // Do NOT cache this incomplete entry
            return metadata;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to quick-read video metadata for {}", videoFile, throwable);
            return new VideoMetadata(videoFile, 0L, "?", -1D, "?", safeModifiedMillis(videoFile), null);
        }
    }

    /**
     * Probe the duration for a single file and cache it. Intended to be called
     * from a background thread. Returns the updated metadata or the original
     * if probing fails.
     */
    public static VideoMetadata probeDurationFor(Path videoFile) {
        if (videoFile == null) return null;
        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);

            // Check cache first
            synchronized (LOCK) {
                CacheEntry cached = CACHE.get(normalized);
                if (cached != null && cached.sizeBytes == size && cached.modifiedMillis == modified
                        && cached.metadata.durationSeconds > 0D) {
                    return cached.metadata;
                }
            }

            double durationSeconds = probeDurationSeconds(normalized);
            String durationDisplay = durationSeconds <= 0D ? "?" : formatDuration(durationSeconds);
            String recordedAt = resolveRecordedAt(normalized, modified);
            Path thumbnail = ensureThumbnail(normalized, modified);

            VideoMetadata metadata = new VideoMetadata(normalized, size, durationDisplay, durationSeconds, recordedAt, modified, thumbnail);
            synchronized (LOCK) {
                CACHE.put(normalized, new CacheEntry(size, modified, metadata));
            }
            return metadata;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Duration probe failed for {}", videoFile, throwable);
            return null;
        }
    }

    public static void clearCache() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    public static boolean isFfprobeAvailable() {
        return detectFfprobe().available;
    }

    private static double probeDurationSeconds(Path file) {
        if (file == null) return -1D;

        // Strategy 1: try ffprobe (preferred, accurate)
        ProbeStatus probe = detectFfprobe();
        if (probe.available) {
            double result = runFfprobeDuration(probe.executable, file);
            if (result > 0D) return result;
            RecordableMod.LOGGER.debug("[Duration] ffprobe found but failed for {}, trying ffmpeg -i fallback", file.getFileName());
        } else {
            RecordableMod.LOGGER.debug("[Duration] ffprobe not available, trying ffmpeg -i fallback for {}", file.getFileName());
        }

        // Strategy 2: use "ffmpeg -i" as fallback (always available if recording works)
        double fallback = runFfmpegDurationFallback(file);
        if (fallback > 0D) {
            RecordableMod.LOGGER.debug("[Duration] ffmpeg -i fallback got duration {}s for {}", String.format(Locale.ROOT, "%.1f", fallback), file.getFileName());
            return fallback;
        }

        // Strategy 3: full decode. Files muxed live by this mod frequently lack a
        // container-level duration header (the "Duration: N/A" case), so neither ffprobe
        // nor "ffmpeg -i" can report it. Decoding the whole file to null and reading the
        // final "time=" progress token yields an accurate duration regardless.
        double decoded = runFfmpegDecodeDuration(file);
        if (decoded > 0D) {
            RecordableMod.LOGGER.debug("[Duration] full-decode got duration {}s for {}", String.format(Locale.ROOT, "%.1f", decoded), file.getFileName());
        } else {
            RecordableMod.LOGGER.warn("[Duration] could not determine duration for {} (no header, decode failed)", file.getFileName());
        }
        return decoded;
    }

    /**
     * Last-resort duration probe: decode the entire file with "ffmpeg -i &lt;file&gt; -f null -"
     * and parse the final "time=HH:MM:SS.xx" token from the progress output. This is slower
     * (it reads the whole file) but works for files whose container header carries no
     * duration, which is common for the live-muxed outputs this mod produces.
     */
    private static double runFfmpegDecodeDuration(Path file) {
        FFmpegEncoder.FfmpegStatus ffmpegStatus = FFmpegEncoder.detectFfmpeg();
        if (!ffmpegStatus.found()) {
            return -1D;
        }

        try {
            Process process = FfmpegBundleManager.ffmpegProcess(
                    ffmpegStatus.executable(),
                    "-nostdin", "-i", file.toAbsolutePath().toString(),
                    "-f", "null",
                    "-"
            ).redirectErrorStream(true).start();

            double lastTime = -1D;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = TIME_PATTERN.matcher(line);
                    while (m.find()) {
                        int hours = Integer.parseInt(m.group(1));
                        int minutes = Integer.parseInt(m.group(2));
                        double seconds = Double.parseDouble(m.group(3));
                        double t = hours * 3600D + minutes * 60D + seconds;
                        if (t > lastTime) lastTime = t;
                    }
                }
            }

            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                RecordableMod.LOGGER.warn("[Duration] full-decode timed out (60s) for {}", file.getFileName());
                return lastTime;
            }
            return lastTime;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("[Duration] full-decode error for {}: {}", file.getFileName(), throwable.getMessage());
            return -1D;
        }
    }

    /**
     * Runs ffprobe to get the duration of a video file.
     */
    private static double runFfprobeDuration(String executable, Path file) {
        try {
            // Query BOTH the container (format) duration and the video stream duration.
            // Files muxed live by this mod (especially MKV) frequently lack a container-level
            // duration header, so `format=duration` returns "N/A" and parsing previously failed
            // (forcing the slower ffmpeg -i fallback). The video stream almost always carries a
            // usable duration. The parsing loop below scans every output line and picks the first
            // value that parses to a positive number, gracefully skipping any "N/A" lines.
            Process process = FfmpegBundleManager.ffmpegProcess(
                    executable,
                    "-nostdin",
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "format=duration:stream=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString().trim();
            }

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                RecordableMod.LOGGER.warn("[Duration] ffprobe timed out (10s) for {}", file.getFileName());
                return -1D;
            }

            if (output.isBlank()) {
                RecordableMod.LOGGER.debug("[Duration] ffprobe returned empty output for {}", file.getFileName());
                return -1D;
            }

            for (String candidate : output.split("\n")) {
                String trimmed = candidate.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    double parsed = Double.parseDouble(trimmed);
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            RecordableMod.LOGGER.debug("[Duration] ffprobe output not parseable for {}: '{}'", file.getFileName(), output);
            return -1D;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("[Duration] ffprobe execution error for {}: {}", file.getFileName(), throwable.getMessage());
            return -1D;
        }
    }

    /**
     * Fallback: use "ffmpeg -i <file>" and parse the "Duration: HH:MM:SS.xx" line
     * from stderr. This works whenever ffmpeg is available (which it must be if
     * recordings work at all).
     */
    private static double runFfmpegDurationFallback(Path file) {
        FFmpegEncoder.FfmpegStatus ffmpegStatus = FFmpegEncoder.detectFfmpeg();
        if (!ffmpegStatus.found()) {
            RecordableMod.LOGGER.warn("[Duration] ffmpeg not found either -- cannot determine duration for {}", file.getFileName());
            return -1D;
        }

        try {
            // "ffmpeg -i <file>" prints info to stderr and exits with code 1 (no output specified).
            // We parse the "Duration: HH:MM:SS.xx" line from the output.
            Process process = FfmpegBundleManager.ffmpegProcess(
                    ffmpegStatus.executable(),
                    "-nostdin", "-i", file.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString();
            }

            // Don't care about exit code -- ffmpeg -i always exits 1 (no output file)
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                RecordableMod.LOGGER.warn("[Duration] ffmpeg -i timed out (10s) for {}", file.getFileName());
                return -1D;
            }

            // Parse "Duration: HH:MM:SS.xx" from the output
            // Pattern matches lines like: "  Duration: 01:23:45.67, start: ..."
            java.util.regex.Matcher matcher = DURATION_PATTERN.matcher(output);
            if (matcher.find()) {
                int hours = Integer.parseInt(matcher.group(1));
                int minutes = Integer.parseInt(matcher.group(2));
                double seconds = Double.parseDouble(matcher.group(3));
                return hours * 3600D + minutes * 60D + seconds;
            }

            RecordableMod.LOGGER.debug("[Duration] ffmpeg -i output did not contain duration for {}", file.getFileName());
            return -1D;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("[Duration] ffmpeg -i fallback error for {}: {}", file.getFileName(), throwable.getMessage());
            return -1D;
        }
    }

    private static ProbeStatus detectFfprobe() {
        // Cache the detection result permanently after the first run. Re-running the
        // full 4-step cascade on every probe call (the previous 30s TTL) produced a
        // wall of duplicate "[ffprobe] Step N ..." lines once per duration probe. The
        // ffmpeg -i / full-decode fallback always covers duration even when ffprobe is
        // genuinely unavailable, so a permanent cache is safe here.
        ProbeStatus cached = cachedProbeStatus;
        if (cached != null) {
            return cached;
        }
        RecordableMod.LOGGER.debug("[ffprobe] Starting detection...");

        // 1. User-configured env var
        String configured = System.getenv("RECORDABLE_FFPROBE_PATH");
        if (configured != null && !configured.isBlank()) {
            RecordableMod.LOGGER.debug("[ffprobe] Step 1: checking env var RECORDABLE_FFPROBE_PATH = '{}'", configured.trim());
            ProbeStatus userStatus = probeExecutable(configured.trim());
            if (userStatus.available) {
                RecordableMod.LOGGER.debug("[ffprobe] Found via env var: {}", configured.trim());
                cachedProbeStatus = userStatus;
                return userStatus;
            }
            RecordableMod.LOGGER.debug("[ffprobe] Env var path not working");
        }

        // 2. Bundled/downloaded ffprobe next to ffmpeg
        try {
            Path bundleDir = FfmpegBundleManager.getBundleDirectory();
            RecordableMod.LOGGER.debug("[ffprobe] Step 2: checking bundle dir: {} (exists={})",
                    bundleDir, bundleDir != null && Files.isDirectory(bundleDir));
            if (bundleDir != null) {
                String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                Path bundledProbe = bundleDir.resolve(probeName);
                boolean isFile = Files.isRegularFile(bundledProbe);
                boolean isReadable = isFile && Files.isReadable(bundledProbe);
                RecordableMod.LOGGER.debug("[ffprobe]   Candidate: {} (isFile={}, isReadable={})",
                        bundledProbe, isFile, isReadable);
                if (isFile && isReadable) {
                    ProbeStatus bundledStatus = probeExecutable(bundledProbe.toAbsolutePath().toString());
                    if (bundledStatus.available) {
                        RecordableMod.LOGGER.debug("[ffprobe] Found in bundle dir: {}", bundledProbe);
                        cachedProbeStatus = bundledStatus;
                        return bundledStatus;
                    }
                    RecordableMod.LOGGER.debug("[ffprobe]   File exists but execution check failed");
                }
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[ffprobe] Error checking bundled path: {}", t.getMessage());
        }

        // 3. Derive ffprobe from detected ffmpeg path (same directory)
        try {
            FFmpegEncoder.FfmpegStatus ffmpegStatus = FFmpegEncoder.detectFfmpeg();
            RecordableMod.LOGGER.debug("[ffprobe] Step 3: ffmpeg found={}, executable='{}'",
                    ffmpegStatus.found(), ffmpegStatus.found() ? ffmpegStatus.executable() : "n/a");
            if (ffmpegStatus.found()) {
                Path ffmpegPath = Path.of(ffmpegStatus.executable());
                Path parent = ffmpegPath.getParent();
                RecordableMod.LOGGER.debug("[ffprobe]   ffmpeg parent dir: {}", parent);
                if (parent != null) {
                    String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                    Path siblingProbe = parent.resolve(probeName);
                    boolean siblingExists = Files.isRegularFile(siblingProbe);
                    RecordableMod.LOGGER.debug("[ffprobe]   Sibling candidate: {} (exists={})", siblingProbe, siblingExists);
                    if (siblingExists) {
                        ProbeStatus siblingStatus = probeExecutable(siblingProbe.toAbsolutePath().toString());
                        if (siblingStatus.available) {
                            RecordableMod.LOGGER.debug("[ffprobe] Found as sibling of ffmpeg: {}", siblingProbe);
                            cachedProbeStatus = siblingStatus;
                            return siblingStatus;
                        }
                        RecordableMod.LOGGER.debug("[ffprobe]   Sibling exists but execution check failed");
                    }
                } else {
                    RecordableMod.LOGGER.debug("[ffprobe]   ffmpeg path has no parent (bare command name on PATH)");
                }
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[ffprobe] Error deriving from ffmpeg path: {}", t.getMessage());
        }

        // 4. System PATH fallback
        RecordableMod.LOGGER.debug("[ffprobe] Step 4: trying system PATH 'ffprobe'...");
        ProbeStatus systemStatus = probeExecutable("ffprobe");
        RecordableMod.LOGGER.debug("[ffprobe] System PATH result: available={}", systemStatus.available);

        if (!systemStatus.available) {
            RecordableMod.LOGGER.debug("[ffprobe] NOT FOUND via any method. Duration will use ffmpeg -i fallback.");
        }

        cachedProbeStatus = systemStatus;
        return systemStatus;
    }

    private static ProbeStatus probeExecutable(String executable) {
        try {
            Process process = FfmpegBundleManager.ffmpegProcess(executable, "-version").redirectErrorStream(true).start();
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                RecordableMod.LOGGER.debug("[ffprobe] Probe timed out (5s) for: {}", executable);
                return new ProbeStatus(false, executable);
            }
            boolean ok = process.exitValue() == 0;
            if (!ok) {
                RecordableMod.LOGGER.debug("[ffprobe] Probe returned exit code {} for: {}", process.exitValue(), executable);
            }
            return new ProbeStatus(ok, executable);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("[ffprobe] Probe exception for '{}': {}", executable, throwable.getMessage());
            return new ProbeStatus(false, executable);
        }
    }

    private static Path ensureThumbnail(Path videoFile, long modifiedMillis) {
        if (videoFile == null) {
            return null;
        }

        FFmpegEncoder.FfmpegStatus ffmpeg = FFmpegEncoder.detectFfmpeg();
        if (!ffmpeg.found()) {
            RecordableMod.LOGGER.debug("Skipping thumbnail for {} because ffmpeg was not found.", videoFile);
            return null;
        }

        Path thumbnailDir = FabricLoader.getInstance().getGameDir().resolve("recordable").resolve("thumbnails");

        try {
            Files.createDirectories(thumbnailDir);
            if (!Files.isDirectory(thumbnailDir) || !Files.isWritable(thumbnailDir)) {
                RecordableMod.LOGGER.warn("Thumbnail directory is not writable: {}", thumbnailDir);
                return null;
            }

            String key = sha1(videoFile.toString() + ":" + modifiedMillis);
            Path thumbnailPath = thumbnailDir.resolve(key + ".png");
            if (Files.exists(thumbnailPath) && safeSize(thumbnailPath) > 0L) {
                return thumbnailPath;
            }

            ProcessBuilder builder = FfmpegBundleManager.ffmpegProcess(
                    ffmpeg.executable(),
                    "-nostdin", "-loglevel", "error",
                    "-y",
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-ss", "00:00:00.500",
                    "-frames:v", "1",
                    "-vf", "thumbnail,scale=192:-1",
                    thumbnailPath.toAbsolutePath().toString()
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();

            boolean exited = process.waitFor(4, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                RecordableMod.LOGGER.debug("Thumbnail extraction timed out for {}", videoFile);
                return null;
            }

            String ffmpegOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0 && Files.exists(thumbnailPath) && safeSize(thumbnailPath) > 0L) {
                return thumbnailPath;
            }

            if (!ffmpegOutput.isBlank()) {
                RecordableMod.LOGGER.debug("Thumbnail extraction failed for {}: {}", videoFile, ffmpegOutput);
            } else {
                RecordableMod.LOGGER.debug("Thumbnail extraction failed for {} with exit code {}", videoFile, process.exitValue());
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Thumbnail extraction failed for {}", videoFile, throwable);
        }

        return null;
    }

    private static String resolveRecordedAt(Path videoFile, long modifiedMillis) {
        try {
            String name = videoFile == null || videoFile.getFileName() == null ? "" : videoFile.getFileName().toString();
            Matcher matcher = FILENAME_TIMESTAMP.matcher(name);
            if (matcher.find()) {
                LocalDateTime parsed = LocalDateTime.parse(matcher.group(1), FILE_TS);
                return DISPLAY_TS.format(parsed);
            }
        } catch (Throwable ignored) {
        }

        Instant instant = Instant.ofEpochMilli(Math.max(0L, modifiedMillis));
        return DISPLAY_TS.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    private static String formatDuration(double durationSeconds) {
        long seconds = Math.max(0L, Math.round(durationSeconds));
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remaining = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    private static long safeSize(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long safeModifiedMillis(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Throwable throwable) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record CacheEntry(long sizeBytes, long modifiedMillis, VideoMetadata metadata) {
    }

    private record ProbeStatus(boolean available, String executable) {
    }
}
