package dev.recordable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles offline (non-realtime) rendering of deferred captures: reads stored frames,
 * applies frame interpolation, muxes audio, and generates the final smooth video.
 *
 * <p><b>Pipeline:</b>
 * 1. Read raw RGB frames from disk
 * 2. Generate intermediate video with frame duplication/interpolation
 * 3. Mux audio (if available)
 * 4. Move final video to recordings directory
 * 5. Clean up temp frames (if config.deferredKeepTempFrames == false)
 * </p>
 */
public final class OfflineRenderer {
    private static final Pattern FFMPEG_PROGRESS_PATTERN = Pattern.compile("frame=\\s*(\\d+)");
    private static final Executor RENDER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Record-able Offline Renderer");
        t.setDaemon(true);
        return t;
    });

    private OfflineRenderer() {
    }

    /**
     * Renders a deferred capture session asynchronously.
     *
     * @param sessionId Session ID to render
     * @param outputDir Output directory for the final video
     * @param keepTempFrames If false, deletes temp frames after successful render
     * @param progressCallback Called periodically with progress (0.0 to 1.0)
     * @return CompletableFuture that completes with the output video path on success,
     *         or exceptionally with an error message
     */
    public static CompletableFuture<Path> renderAsync(String sessionId, Path outputDir,
                                                       boolean keepTempFrames,
                                                       Consumer<Double> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renderSync(sessionId, outputDir, keepTempFrames, progressCallback);
            } catch (Exception e) {
                RecordableMod.LOGGER.error("[OfflineRenderer] Render failed for session {}: {}", sessionId, e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        }, RENDER_EXECUTOR);
    }

    /**
     * Synchronous render (blocks until complete).
     */
    private static Path renderSync(String sessionId, Path outputDir, boolean keepTempFrames,
                                    Consumer<Double> progressCallback) throws Exception {
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        DeferredCaptureManager.DeferredRecordingMetadata meta = dcm.getMetadata(sessionId);
        if (meta == null) {
            throw new IOException("Session metadata not found: " + sessionId);
        }

        dcm.markRendering(sessionId);
        RecordableMod.LOGGER.info("[OfflineRenderer] Starting render: session={} {}x{} @ {}fps -> {}fps ({})",
                sessionId, meta.width, meta.height, meta.captureFps, meta.targetFps, meta.interpolation);

        Path framesDir = dcm.getFramesDir(sessionId);
        Path audioPath = dcm.getAudioPath(sessionId);
        Path tempVideoPath = dcm.getSessionDir(sessionId).resolve("temp_video.mkv");
        // Route the finished video into its subfolder (e.g. clips/kills for auto-clips) so each
        // clip type stays organized; manual recordings have no subfolder and land in the root.
        Path targetDir = outputDir;
        if (meta.outputSubfolder != null && !meta.outputSubfolder.isBlank()) {
            targetDir = outputDir.resolve(meta.outputSubfolder);
        }
        Files.createDirectories(targetDir);
        Path finalVideoPath = targetDir.resolve(meta.originalName + ".mkv");

        // Step 1: Generate video from raw RGB frames
        generateVideoFromFrames(framesDir, tempVideoPath, meta, progressCallback);

        // Step 2: Mux audio (if available)
        if (audioPath != null && Files.exists(audioPath)) {
            Path tempWithAudio = dcm.getSessionDir(sessionId).resolve("temp_with_audio.mkv");
            muxAudio(tempVideoPath, audioPath, tempWithAudio);
            Files.delete(tempVideoPath);
            tempVideoPath = tempWithAudio;
        }

        // Step 3: Move final video to output directory
        Files.createDirectories(targetDir);
        if (Files.exists(finalVideoPath)) {
            // Add timestamp suffix to avoid overwriting
            String baseName = meta.originalName;
            String timestamp = String.valueOf(System.currentTimeMillis());
            finalVideoPath = targetDir.resolve(baseName + "-" + timestamp + ".mkv");
        }
        Files.move(tempVideoPath, finalVideoPath);

        // Step 4: Clean up temp frames (if requested)
        if (!keepTempFrames) {
            dcm.deleteSession(sessionId);
            RecordableMod.LOGGER.info("[OfflineRenderer] Deleted temp frames for session {}", sessionId);
        } else {
            dcm.markCompleted(sessionId);
            RecordableMod.LOGGER.info("[OfflineRenderer] Kept temp frames for session {}", sessionId);
        }

        RecordableMod.LOGGER.info("[OfflineRenderer] Render complete: {}", finalVideoPath);
        if (progressCallback != null) {
            progressCallback.accept(1.0);
        }

        return finalVideoPath;
    }

    /**
     * Generates video from raw RGB frames using FFmpeg.
     */
    private static void generateVideoFromFrames(Path framesDir, Path outputPath,
                                                 DeferredCaptureManager.DeferredRecordingMetadata meta,
                                                 Consumer<Double> progressCallback) throws Exception {
        String ffmpegPath = detectFfmpegForOfflineRender();
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            throw new IOException("FFmpeg not found. Cannot render offline. "
                    + "Install FFmpeg via the mod's download screen, set RECORDABLE_FFMPEG_PATH, "
                    + "or add ffmpeg to your system PATH.");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-nostdin");
        cmd.add("-y"); // Overwrite output

        // Input: raw RGB frames
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("rgb24");
        cmd.add("-s");
        cmd.add(meta.width + "x" + meta.height);
        cmd.add("-r");
        cmd.add(String.valueOf(meta.captureFps));
        cmd.add("-i");
        cmd.add("pipe:0"); // Will concatenate frames via stdin

        // Frame interpolation filter. This is a single-stream (one input, one output)
        // filter, so it must go through -vf, NOT -filter_complex. A -filter_complex
        // graph with a labeled output pad requires an explicit -map for that label;
        // without one FFmpeg aborts immediately with EINVAL (exit code -22) and the
        // frame-feeder pipe breaks. -vf needs no labels and no map.
        String videoFilter = getInterpolationFilter(meta.captureFps, meta.targetFps, meta.interpolation);
        if (videoFilter != null && !videoFilter.isEmpty()) {
            cmd.add("-vf");
            cmd.add(videoFilter);
        }

        // Output: H.264 video at target FPS.
        //
        // The deferred renderer previously used the legacy "mpeg4" (MPEG-4 Part 2) encoder with
        // "-q:v 5". Because the input is full-range rgb24 read straight from the framebuffer, that
        // encoder produced noticeably dark/washed output compared to the realtime path (which uses
        // libx264). We now match the realtime encoder: libx264 -> yuv420p. libx264's default
        // rgb->yuv conversion preserves the on-screen brightness, so deferred output looks the same
        // as a realtime recording instead of coming out too dark.
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("medium");
        cmd.add("-crf");
        cmd.add("18"); // Visually lossless / high quality
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-r");
        cmd.add(String.valueOf(meta.targetFps));
        cmd.add(outputPath.toString());

        RecordableMod.LOGGER.info("[OfflineRenderer] FFmpeg command: {}", String.join(" ", cmd));

        ProcessBuilder pb = FfmpegBundleManager.ffmpegProcess(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Feed frames to FFmpeg stdin in a separate thread
        Thread frameFeeder = new Thread(() -> feedFramesToFFmpeg(process, framesDir, meta.totalFrames), "FFmpeg Frame Feeder");
        frameFeeder.start();

        // Monitor FFmpeg progress
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                RecordableMod.LOGGER.debug("[OfflineRenderer FFmpeg] {}", line);

                // Parse progress
                if (progressCallback != null) {
                    Matcher matcher = FFMPEG_PROGRESS_PATTERN.matcher(line);
                    if (matcher.find()) {
                        int currentFrame = Integer.parseInt(matcher.group(1));
                        int expectedFrames = (int) ((meta.totalFrames / (double) meta.captureFps) * meta.targetFps);
                        double progress = Math.min(0.95, (double) currentFrame / expectedFrames);
                        progressCallback.accept(progress);
                    }
                }
            }
        }

        int exitCode = process.waitFor();
        frameFeeder.join();

        if (exitCode != 0) {
            throw new IOException("FFmpeg exited with code " + exitCode);
        }
    }

    /**
     * Feeds raw RGB frames from disk to FFmpeg stdin.
     */
    private static void feedFramesToFFmpeg(Process process, Path framesDir, int totalFrames) {
        try (var outputStream = process.getOutputStream()) {
            for (int i = 1; i <= totalFrames; i++) {
                Path framePath = framesDir.resolve(String.format("f%05d.rgb", i));
                if (!Files.exists(framePath)) {
                    RecordableMod.LOGGER.warn("[OfflineRenderer] Missing frame: {}", framePath);
                    continue;
                }
                byte[] frameData = Files.readAllBytes(framePath);
                outputStream.write(frameData);
            }
            outputStream.flush();
        } catch (IOException e) {
            RecordableMod.LOGGER.error("[OfflineRenderer] Failed to feed frames: {}", e.getMessage());
        }
    }

    /**
     * Returns FFmpeg filter for frame interpolation.
     */
    private static String getInterpolationFilter(int captureFps, int targetFps, String interpolation) {
        if ("none".equals(interpolation)) {
            // No filter, just encode at target FPS (FFmpeg will duplicate frames)
            return null;
        } else if ("duplicate".equals(interpolation)) {
            // Smart CFR duplication using fps filter (plain -vf filter, no pad labels).
            return String.format("fps=%d", targetFps);
        } else if ("motion".equals(interpolation)) {
            // Motion interpolation using minterpolate (RIFE/DAIN require external models).
            // Plain -vf filter, no pad labels.
            return String.format("minterpolate=fps=%d:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1", targetFps);
        }
        return null;
    }

    /**
     * Muxes audio into the video.
     */
    private static void muxAudio(Path videoPath, Path audioPath, Path outputPath) throws Exception {
        String ffmpegPath = detectFfmpegForOfflineRender();
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            throw new IOException("FFmpeg not found.");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(videoPath.toString());
        cmd.add("-i");
        cmd.add(audioPath.toString());
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add("-shortest");
        cmd.add(outputPath.toString());

        RecordableMod.LOGGER.info("[OfflineRenderer] Muxing audio: {}", String.join(" ", cmd));

        ProcessBuilder pb = FfmpegBundleManager.ffmpegProcess(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                RecordableMod.LOGGER.debug("[OfflineRenderer Audio Mux] {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Audio mux failed with exit code " + exitCode);
        }
    }

    /**
     * Detects FFmpeg for offline rendering using a priority-based fallback chain:
     * <ol>
     *   <li>Bundled/downloaded FFmpeg (managed by {@link FfmpegBundleManager})</li>
     *   <li>RECORDABLE_FFMPEG_PATH environment variable</li>
     *   <li>System PATH (tries "ffmpeg" directly)</li>
     * </ol>
     *
     * @return absolute path to ffmpeg executable, or null if not found
     */
    private static String detectFfmpegForOfflineRender() {
        // 1. Bundled/downloaded FFmpeg
        String bundledPath = FfmpegBundleManager.getBundledFfmpegPath();
        if (bundledPath != null && !bundledPath.isBlank()) {
            return bundledPath;
        }

        // 2. RECORDABLE_FFMPEG_PATH environment variable
        String envPath = System.getenv("RECORDABLE_FFMPEG_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Path.of(envPath.trim());
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
        }

        // 3. System PATH (try running "ffmpeg" directly)
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode == 0) {
                return "ffmpeg";
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
