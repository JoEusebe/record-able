package dev.recordable.screen;

import dev.recordable.FFmpegEncoder;
import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableMod;
import dev.recordable.VideoMetadata;
import dev.recordable.compat.RenderHelper;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemePreset;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedPanel;
import io.wispforest.owo.ui.core.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * In-game themed media player controller.
 * Playback is a frame-only in-game preview, decoded with ffmpeg.
 */
public final class VideoPlayerScreen extends Screen {
    private static final int BTN_W = 110;
    private static final int BTN_H = 20;
    private static final int GAP = 6;
    private static final double END_RESTART_THRESHOLD_SECONDS = 0.15D;
    private static final int FRAME_LOOKBACK_NEAR = 18;
    private static final int FRAME_LOOKBACK_FAR = 900;
    private static final long PREVIEW_ENSURE_RETRY_NANOS = 500_000_000L;
    private static final double PREVIEW_EXTRACTION_SCALE = 2.0D;
    private static final double[] SPEEDS = {0.5D, 1.0D, 1.5D, 2.0D};

    private final File videoFile;
    private final Screen parent;
    private final VideoMetadata quickMetadata;

    private String videoInfo = "Loading...";
    private String errorMessage = null;
    private double durationSeconds = -1D;
    private double playbackFps = 30D;
    private int videoPixelWidth = 0;
    private int videoPixelHeight = 0;

    private Process ffplayProcess;
    private Thread playbackMonitorThread;
    private boolean playing;
    private boolean silentPreviewPlayback;
    private int volumePercent = 100;
    private int speedIndex = 1;
    private long playbackStartNanos = 0L;
    private double playbackStartOffsetSeconds = 0D;
    private double currentSeconds = 0D;
    private double extractionFps = 30D;
    private double extractionStartSeconds = 0D;

    private ButtonWidget playPauseButton;
    private ButtonWidget openVideoButton;
    private ButtonWidget speedButton;
    private ButtonWidget volumeDownButton;
    private ButtonWidget volumeUpButton;

    private int progressLeft;
    private int progressTop;
    private int progressWidth;
    private int progressHeight;
    private boolean isDraggingProgress;
    private boolean wasPlayingBeforeDragSeek;
    private long nextPreviewEnsureNanos;
    /** Fixed identifier for the single reused frame texture. */
    private static final Identifier FRAME_TEXTURE_ID =
            dev.recordable.VersionHelper.id(dev.recordable.RecordableMod.MOD_ID, "player-frame/active");
    private NativeImageBackedTexture frameTexture;
    private boolean frameTextureRegistered = false;
    private int loadedFrameIndex = -1;
    private Path extractedFramesDir;
    private Process frameExtractProcess;
    /** Last exception message from frame loading, shown in the viewer when no frame renders. */
    private String frameLoadError = null;
    /** Thread that drains ffmpeg stderr to capture error lines. */
    private Thread ffmpegStderrThread;

    public VideoPlayerScreen(Path videoPath, Screen parent) {
        super(Text.literal("Media Player"));
        this.videoFile = videoPath.toFile();
        this.parent = parent;
        this.quickMetadata = VideoMetadata.readQuick(videoPath);
        loadVideoInfo();
    }

    private void loadVideoInfo() {
        FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
        if (!status.found()) {
            videoInfo = "FFmpeg not found, metadata unavailable";
            return;
        }
        String infoFromProbe = tryFfprobeInfo(status);
        if (infoFromProbe != null) {
            videoInfo = infoFromProbe;
            return;
        }
        String infoFromFfmpeg = tryFfmpegInfo(status);
        if (infoFromFfmpeg != null) {
            videoInfo = infoFromFfmpeg;
            return;
        }
        long sizeBytes = videoFile.length();
        videoInfo = String.format(Locale.ROOT, "Size: %.2f MB", sizeBytes / (1024.0 * 1024.0));
    }

    private String tryFfprobeInfo(FFmpegEncoder.FfmpegStatus ffmpegStatus) {
        String ffprobeExecutable = resolveSiblingExecutable(ffmpegStatus.executable(), "ffprobe");
        List<String> command = new ArrayList<>();
        command.add(ffprobeExecutable);
        command.add("-v");
        command.add("error");
        command.add("-select_streams");
        command.add("v:0");
        command.add("-show_entries");
        command.add("stream=width,height,r_frame_rate:format=duration");
        command.add("-of");
        command.add("default=noprint_wrappers=1:nokey=0");
        command.add(videoFile.getAbsolutePath());
        try {
            Process process = FfmpegBundleManager.ffmpegProcess(command).redirectErrorStream(true).start();
            Integer parsedWidth = null;
            Integer parsedHeight = null;
            String parsedFps = null;
            String parsedDuration = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("width=")) {
                        parsedWidth = Integer.parseInt(trimmed.substring("width=".length()).trim());
                    } else if (trimmed.startsWith("height=")) {
                        parsedHeight = Integer.parseInt(trimmed.substring("height=".length()).trim());
                    } else if (trimmed.startsWith("r_frame_rate=")) {
                        parsedFps = trimmed.substring("r_frame_rate=".length()).trim();
                    } else if (trimmed.startsWith("duration=")) {
                        parsedDuration = trimmed.substring("duration=".length()).trim();
                    }
                }
            }
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || parsedWidth == null || parsedHeight == null) return null;
            videoPixelWidth = parsedWidth;
            videoPixelHeight = parsedHeight;
            String resolution = parsedWidth + "x" + parsedHeight;
            String fps = "?";
            if (parsedFps != null && !parsedFps.isBlank() && !"N/A".equalsIgnoreCase(parsedFps)) {
                String[] fpsParts = parsedFps.split("/");
                if (fpsParts.length == 2) {
                    double n = Double.parseDouble(fpsParts[0]);
                    double d = Double.parseDouble(fpsParts[1]);
                    if (d > 0D) {
                        playbackFps = Math.max(1D, n / d);
                        fps = String.format(Locale.ROOT, "%.0f", playbackFps);
                    }
                } else {
                    playbackFps = Math.max(1D, Double.parseDouble(parsedFps));
                    fps = String.format(Locale.ROOT, "%.0f", playbackFps);
                }
            }
            if (parsedDuration != null && !parsedDuration.isBlank() && !"N/A".equalsIgnoreCase(parsedDuration)) {
                durationSeconds = Double.parseDouble(parsedDuration);
            }
            return resolution + " @ " + fps + " fps  |  Duration: " + formatTime(durationSeconds);
        } catch (Throwable t) {
            return null;
        }
    }

    private String tryFfmpegInfo(FFmpegEncoder.FfmpegStatus ffmpegStatus) {
        try {
            List<String> infoCmd = new ArrayList<>();
            infoCmd.add(ffmpegStatus.executable());
            infoCmd.add("-nostdin");
            infoCmd.add("-i");
            infoCmd.add(videoFile.getAbsolutePath());
            Process process = FfmpegBundleManager.ffmpegProcess(infoCmd).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                output = sb.toString();
            }
            process.waitFor(10, TimeUnit.SECONDS);
            if (process.isAlive()) process.destroyForcibly();

            String resolution = "?";
            String fps = "?";
            java.util.regex.Matcher dur = java.util.regex.Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+\\.\\d+)").matcher(output);
            if (dur.find()) {
                int h = Integer.parseInt(dur.group(1));
                int m = Integer.parseInt(dur.group(2));
                double s = Double.parseDouble(dur.group(3));
                durationSeconds = h * 3600D + m * 60D + s;
            }
            java.util.regex.Matcher vid = java.util.regex.Pattern.compile("Stream.*Video:.*?(\\d{2,5})x(\\d{2,5})").matcher(output);
            if (vid.find()) {
                videoPixelWidth = Integer.parseInt(vid.group(1));
                videoPixelHeight = Integer.parseInt(vid.group(2));
                resolution = vid.group(1) + "x" + vid.group(2);
            }
            java.util.regex.Matcher fpsMatch = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+fps").matcher(output);
            if (fpsMatch.find()) {
                playbackFps = Math.max(1D, Double.parseDouble(fpsMatch.group(1)));
                fps = String.format(Locale.ROOT, "%.0f", playbackFps);
            }
            return resolution + " @ " + fps + " fps  |  Duration: " + formatTime(durationSeconds);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();

        int panelW = Math.max(480, Math.min(900, this.width - 40));
        int panelH = Math.max(280, Math.min(420, this.height - 40));
        int left = (this.width - panelW) / 2;
        int top = (this.height - panelH) / 2;
        int railLeft = left + 14;
        int railTop = top + 34;
        int railW = Math.max(172, Math.min(220, panelW / 4));
        int pairW = (railW - GAP) / 2;
        int y = railTop;

        addDrawableChild(ThemedButton.create(railLeft, y, pairW, BTN_H, Text.literal("-10s"), b -> seekRelative(-10D)));
        addDrawableChild(ThemedButton.create(railLeft + pairW + GAP, y, pairW, BTN_H, Text.literal("+10s"), b -> seekRelative(10D)));
        y += BTN_H + GAP;
        speedButton = addDrawableChild(ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal(speedLabel()), b -> cycleSpeed()));
        y += BTN_H + GAP;
        volumeDownButton = addDrawableChild(ThemedButton.create(railLeft, y, pairW, BTN_H, Text.literal("Vol -"), b -> changeVolume(-10)));
        volumeUpButton = addDrawableChild(ThemedButton.create(railLeft + pairW + GAP, y, pairW, BTN_H, Text.literal("Vol +"), b -> changeVolume(10)));
        y += BTN_H + GAP;

        playPauseButton = addDrawableChild(ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal(playing ? "Pause" : "Play"), b -> togglePlayPause()));
        y += BTN_H + GAP;
        openVideoButton = addDrawableChild(ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal("Open Video"), b -> openInDefaultPlayer()));
        y += BTN_H + GAP;
        if (PlatformUtils.isAndroid()) {
            Tooltip androidTip = Tooltip.of(Text.literal(
                    "Opening folders is unavailable on Android launcher sandbox."));
            ButtonWidget openFolderDisabled = ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal("Open Folder"), b -> {});
            openFolderDisabled.active = false;
            openFolderDisabled.setTooltip(androidTip);
            addDrawableChild(openFolderDisabled);
        } else {
            addDrawableChild(ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal("Open Folder"), b -> openRecordingsFolder()));
        }
        y += BTN_H + GAP;
        addDrawableChild(ThemedButton.create(railLeft, y, railW, BTN_H, Text.literal("Back"), b -> close()));

        updateControlLabels();
        ensurePreviewFrames();
    }

    private String speedLabel() {
        return "Speed: x" + String.format(Locale.ROOT, "%.1f", SPEEDS[speedIndex]);
    }

    private void togglePlayPause() {
        if (playing) {
            pausePlayback();
        } else {
            if (durationSeconds > 0D && currentSeconds >= durationSeconds - END_RESTART_THRESHOLD_SECONDS) {
                currentSeconds = 0D;
            }
            startPlaybackAt(currentSeconds);
        }
    }

    private void pausePlayback() {
        updateCurrentPositionFromClock();
        stopProcess();
        silentPreviewPlayback = false;
        playing = false;
        refreshPreviewAtCurrentPosition();
        updateControlLabels();
    }
    private void seekRelative(double delta) {
        double target = clampToDuration(currentSeconds + delta);
        if (playing) {
            startPlaybackAt(target);
        } else {
            currentSeconds = target;
            refreshPreviewAtCurrentPosition();
        }
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        if (playing) startPlaybackAt(currentSeconds);
        updateControlLabels();
    }

    private void changeVolume(int delta) {
        // Frame-only preview has no audio output.
        updateControlLabels();
    }

    private void updateControlLabels() {
        if (playPauseButton != null) playPauseButton.setMessage(Text.literal(playing ? "Pause" : "Play"));
        if (speedButton != null) speedButton.setMessage(Text.literal(speedLabel()));
        if (volumeDownButton != null) volumeDownButton.active = false;
        if (volumeUpButton != null) volumeUpButton.active = false;
    }

    private void ensurePreviewFrames() {
        if (extractedFramesDir != null || playing) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextPreviewEnsureNanos) {
            return;
        }
        nextPreviewEnsureNanos = now + PREVIEW_ENSURE_RETRY_NANOS;
        FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
        if (!status.found()) {
            return;
        }
        int[] viewerSize = computeViewerAreaSize();
        startFrameExtraction(status.executable(), currentSeconds, viewerSize[0], viewerSize[1]);
    }

    private void refreshPreviewAtCurrentPosition() {
        if (playing) {
            return;
        }
        FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
        if (!status.found()) {
            return;
        }
        int[] viewerSize = computeViewerAreaSize();
        startFrameExtraction(status.executable(), currentSeconds, viewerSize[0], viewerSize[1]);
    }

    private void startPlaybackAt(double seconds) {
        try {
            FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
            if (!status.found()) {
                errorMessage = "FFmpeg is not available for frame preview.";
                return;
            }
            stopProcess();
            int[] viewerSize = computeViewerAreaSize();
            startFrameExtraction(status.executable(), seconds, viewerSize[0], viewerSize[1]);
            playing = true;
            silentPreviewPlayback = true;
            playbackStartNanos = System.nanoTime();
            playbackStartOffsetSeconds = seconds;
            currentSeconds = seconds;
            errorMessage = null;
            updateControlLabels();
        } catch (Exception e) {
            playing = false;
            silentPreviewPlayback = false;
            errorMessage = "Frame preview failed: " + e.getMessage();
            updateControlLabels();
        }
    }

    private void stopProcess() {
        ffplayProcess = null;
        playbackMonitorThread = null;
        silentPreviewPlayback = false;
        stopFrameExtraction();
    }

    private void updateCurrentPositionFromClock() {
        if (!playing) return;
        double elapsed = Math.max(0D, (System.nanoTime() - playbackStartNanos) / 1_000_000_000D);
        currentSeconds = clampToDuration(playbackStartOffsetSeconds + elapsed * SPEEDS[speedIndex]);
        if (durationSeconds > 0D && currentSeconds >= durationSeconds - 0.001D) {
            currentSeconds = durationSeconds;
            playing = false;
        }
    }

    private double clampToDuration(double value) {
        if (durationSeconds <= 0D) return Math.max(0D, value);
        return Math.max(0D, Math.min(durationSeconds, value));
    }

    private int[] computeViewerAreaSize() {
        int panelW = Math.max(480, Math.min(900, this.width - 40));
        int panelH = Math.max(280, Math.min(420, this.height - 40));
        int railW = Math.max(172, Math.min(220, panelW / 4));
        int maxW = Math.max(320, panelW - railW - 40);
        int maxH = Math.max(180, panelH - 108);
        if (videoPixelWidth <= 0 || videoPixelHeight <= 0) {
            return new int[]{maxW, maxH};
        }
        double aspect = videoPixelWidth / (double) videoPixelHeight;
        int fittedW = maxW;
        int fittedH = (int) Math.round(fittedW / aspect);
        if (fittedH > maxH) {
            fittedH = maxH;
            fittedW = (int) Math.round(fittedH * aspect);
        }
        return new int[]{Math.max(160, fittedW), Math.max(90, fittedH)};
    }

    private int[] computeExtractionTargetSize(int targetW, int targetH) {
        // targetW/targetH already come from computeViewerAreaSize() which respects
        // the video's aspect ratio. Using the viewer dimensions directly avoids the
        // independent W/H clamping that previously broke aspect ratio for non-16:9 content.
        int scaledW = Math.max(64, (int) Math.round(targetW * PREVIEW_EXTRACTION_SCALE));
        int scaledH = Math.max(64, (int) Math.round(targetH * PREVIEW_EXTRACTION_SCALE));
        if (videoPixelWidth > 0 && videoPixelHeight > 0 && scaledW > 0 && scaledH > 0) {
            double capScale = Math.min(
                    1.0D,
                    Math.min(videoPixelWidth / (double) scaledW, videoPixelHeight / (double) scaledH));
            if (capScale < 1.0D) {
                scaledW = Math.max(64, (int) Math.floor(scaledW * capScale));
                scaledH = Math.max(64, (int) Math.floor(scaledH * capScale));
            }
        }
        return new int[]{scaledW, scaledH};
    }

    private boolean drawThumbnailPreview(DrawContext context, int x, int y, int width, int height) {
        return false; // thumbnail display removed
    }

    private boolean drawPlaybackFrame(DrawContext context, int x, int y, int width, int height) {
        if (extractedFramesDir == null) {
            return false;
        }
        double relativeSeconds = Math.max(0D, currentSeconds - extractionStartSeconds);
        int targetFrameNumber = Math.max(1, 1 + (int) Math.floor(relativeSeconds * Math.max(1D, extractionFps)));
        Path framePath = null;
        int resolvedFrameNumber = -1;
        int lookbackLimit = loadedFrameIndex > 0 ? FRAME_LOOKBACK_NEAR : FRAME_LOOKBACK_FAR;
        for (int lookback = 0; lookback <= lookbackLimit; lookback++) {
            int candidate = targetFrameNumber - lookback;
            if (candidate < 1) {
                break;
            }
            Path candidatePath = extractedFramesDir.resolve(String.format(Locale.ROOT, "frame-%06d.png", candidate));
            if (Files.exists(candidatePath)) {
                framePath = candidatePath;
                resolvedFrameNumber = candidate;
                break;
            }
        }
        if (framePath == null) {
            if (!frameTextureRegistered || frameTexture == null) {
                return false;
            }
        } else if (loadedFrameIndex != resolvedFrameNumber) {
            if (!loadFrameTexture(framePath, resolvedFrameNumber)) {
                return false;
            }
        }
        if (!frameTextureRegistered || frameTexture == null) {
            return false;
        }
        try {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME_TEXTURE_ID, x, y, 0.0F, 0.0F, width, height, width, height);
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to draw frame {}", framePath, throwable);
            return false;
        }
    }

    private boolean loadThumbnailTexture(Path thumbnailPath) {
        return false; // thumbnail display removed
    }

    private void releaseThumbnailTexture() {
        // no-op, thumbnail display removed
    }

    private boolean loadFrameTexture(Path framePath, int frameIndex) {
        MinecraftClient client = this.client == null ? MinecraftClient.getInstance() : this.client;
        if (client == null || client.getTextureManager() == null || framePath == null) {
            return false;
        }
        try {
            if (!Files.exists(framePath) || !Files.isReadable(framePath)) {
                return false;
            }
            NativeImage image;
            try (java.io.InputStream stream = Files.newInputStream(framePath)) {
                image = NativeImage.read(stream);
            }
            if (frameTexture == null) {
                // First load: create the texture and register it once.
                frameTexture = new NativeImageBackedTexture(() -> "recordable-player-frame", image);
                client.getTextureManager().registerTexture(FRAME_TEXTURE_ID, frameTexture);
                frameTextureRegistered = true;
            } else {
                // Subsequent loads: swap the image and re-upload to the existing GPU texture.
                frameTexture.setImage(image);
                frameTexture.upload();
            }
            this.loadedFrameIndex = frameIndex;
            this.frameLoadError = null;
            return true;
        } catch (Throwable throwable) {
            this.frameLoadError = throwable.getMessage();
            RecordableMod.LOGGER.warn("Failed to load frame {}", framePath, throwable);
            return false;
        }
    }

    private void releaseFrameTexture() {
        MinecraftClient client = this.client == null ? MinecraftClient.getInstance() : this.client;
        // Destroy from TextureManager first (this closes the GPU resources).
        if (frameTextureRegistered) {
            try {
                if (client != null && client.getTextureManager() != null) {
                    client.getTextureManager().destroyTexture(FRAME_TEXTURE_ID);
                }
            } catch (Throwable ignored) {
            }
            frameTextureRegistered = false;
        } else {
            // If not registered, close the object directly if it was ever created.
            try {
                if (frameTexture != null) {
                    frameTexture.close();
                }
            } catch (Throwable ignored) {
            }
        }
        frameTexture = null;
        loadedFrameIndex = -1;
        frameLoadError = null;
    }

    private void startFrameExtraction(String ffmpegExecutable, double seconds, int targetW, int targetH) {
        stopFrameExtraction();
        try {
            // Cap at 24fps for frame extraction - sufficient for smooth playback
            // preview while keeping disk I/O manageable for high-fps source videos.
            extractionFps = Math.max(8D, Math.min(24D, playbackFps));
            int[] extractionSize = computeExtractionTargetSize(targetW, targetH);
            extractedFramesDir = Files.createTempDirectory("recordable-player-frames-");
            List<String> command = new ArrayList<>();
            command.add(ffmpegExecutable);
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            if (seconds > 0.01D) {
                command.add("-ss");
                command.add(String.format(Locale.ROOT, "%.3f", seconds));
            }
            command.add("-i");
            command.add(videoFile.getAbsolutePath());
            command.add("-an");
            command.add("-sn");
            command.add("-dn");
            command.add("-vf");
            command.add(String.format(Locale.ROOT,
                    "fps=%.3f,scale=%d:%d:flags=lanczos",
                    extractionFps,
                    extractionSize[0], extractionSize[1]));
            command.add(extractedFramesDir.resolve("frame-%06d.png").toString());
            ProcessBuilder pb = FfmpegBundleManager.ffmpegProcess(command).redirectErrorStream(true);
            frameExtractProcess = pb.start();
            loadedFrameIndex = -1;
            nextPreviewEnsureNanos = 0L;
            frameLoadError = null;
            extractionStartSeconds = Math.max(0D, seconds);
            startFfmpegStderrDrain(frameExtractProcess);
        } catch (IOException e) {
            errorMessage = "Failed to extract frames: " + e.getMessage();
        }
    }

    private void startFfmpegStderrDrain(Process process) {
        if (ffmpegStderrThread != null) {
            ffmpegStderrThread.interrupt();
        }
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        final String msg = line.trim();
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc != null) {
                            mc.execute(() -> {
                                if (frameLoadError == null) {
                                    frameLoadError = "ffmpeg: " + msg;
                                }
                                RecordableMod.LOGGER.warn("[ffmpeg] {}", msg);
                            });
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "recordable-ffmpeg-stderr");
        t.setDaemon(true);
        t.start();
        ffmpegStderrThread = t;
    }

    private void stopFrameExtraction() {
        if (ffmpegStderrThread != null) {
            ffmpegStderrThread.interrupt();
            ffmpegStderrThread = null;
        }
        if (frameExtractProcess != null) {
            try {
                frameExtractProcess.destroy();
                if (!frameExtractProcess.waitFor(250, TimeUnit.MILLISECONDS)) {
                    frameExtractProcess.destroyForcibly();
                }
            } catch (Throwable ignored) {
            } finally {
                frameExtractProcess = null;
            }
        }
        if (extractedFramesDir != null) {
            try {
                Files.walk(extractedFramesDir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
            extractedFramesDir = null;
        }
        extractionStartSeconds = 0D;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0 && mouseX >= progressLeft && mouseX <= progressLeft + progressWidth
                && mouseY >= progressTop && mouseY <= progressTop + progressHeight) {
            isDraggingProgress = true;
            wasPlayingBeforeDragSeek = playing;
            if (playing) {
                pausePlayback();
            }
            if (durationSeconds > 0D) {
                double t = (mouseX - progressLeft) / Math.max(1D, progressWidth);
                double target = clampToDuration(durationSeconds * t);
                currentSeconds = target;
            }
            return true;
        }
        isDraggingProgress = false;
        wasPlayingBeforeDragSeek = false;
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0 && isDraggingProgress && durationSeconds > 0D) {
            double t = (click.x() - progressLeft) / Math.max(1D, progressWidth);
            double target = clampToDuration(durationSeconds * t);
            currentSeconds = target;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            boolean resumePlayback = isDraggingProgress && wasPlayingBeforeDragSeek;
            isDraggingProgress = false;
            wasPlayingBeforeDragSeek = false;
            if (resumePlayback) {
                startPlaybackAt(currentSeconds);
            } else {
                refreshPreviewAtCurrentPosition();
            }
        }
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        drawPreviewBackground(context);
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();

        int panelW = Math.max(480, Math.min(900, this.width - 40));
        int panelH = Math.max(280, Math.min(420, this.height - 40));
        int left = (this.width - panelW) / 2;
        int top = (this.height - panelH) / 2;
        int right = left + panelW;
        int bottom = top + panelH;

        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(context, left, top, right, bottom);
        } else {
            ThemedPanel.drawPanel(context, left, top, right, bottom);
        }

        if (playing) {
            updateCurrentPositionFromClock();
        } else if (extractedFramesDir == null && !isDraggingProgress) {
            ensurePreviewFrames();
        }

        ThemedPanel.drawSectionHeader(context, this.textRenderer, "Media Player", left + 14, top + 12, panelW - 28);

        int railLeft = left + 14;
        int railW = Math.max(172, Math.min(220, panelW / 4));
        int viewerLeft = railLeft + railW + 12;
        int viewerRight = right - 14;
        int viewerTop = top + 34;
        int viewerBottom = Math.max(viewerTop + 88, bottom - 74);

        context.fill(viewerLeft, viewerTop, viewerRight, viewerBottom, colors.sectionBackground);
        boolean drewVideoFrame = drawPlaybackFrame(context, viewerLeft + 1, viewerTop + 1,
                Math.max(1, viewerRight - viewerLeft - 2), Math.max(1, viewerBottom - viewerTop - 2));
        // Borders drawn AFTER the frame so they are always visible on top of any content.
        context.fill(viewerLeft, viewerTop, viewerRight, viewerTop + 2, colors.accent);
        context.fill(viewerLeft, viewerBottom - 1, viewerRight, viewerBottom, colors.panelBorder);
        context.fill(viewerLeft, viewerTop, viewerLeft + 1, viewerBottom, colors.panelBorder);
        context.fill(viewerRight - 1, viewerTop, viewerRight, viewerBottom, colors.panelBorder);
        if (!drewVideoFrame) {
            String previewLabel;
            if (playing) {
                previewLabel = frameLoadError != null ? "Video error: " + frameLoadError
                        : extractedFramesDir != null ? "Extracting frames..." : "Starting...";
            } else {
                previewLabel = frameLoadError != null ? "Load error: " + frameLoadError
                        : extractedFramesDir != null ? "Loading..." : "Press Play";
            }
            // Clip the label to fit inside the viewer width.
            int viewerInnerW = viewerRight - viewerLeft - 8;
            if (this.textRenderer.getWidth(previewLabel) > viewerInnerW) {
                previewLabel = this.textRenderer.trimToWidth(previewLabel, Math.max(8, viewerInnerW - this.textRenderer.getWidth("..."))) + "...";
            }
            int previewW = this.textRenderer.getWidth(previewLabel);
            RenderHelper.drawText(context, this.textRenderer, previewLabel,
                    viewerLeft + ((viewerRight - viewerLeft - previewW) / 2),
                    viewerTop + ((viewerBottom - viewerTop) / 2) - 4, colors.textMuted);
        } else if (!playing) {
            int overlayY = viewerBottom - 14;
            context.fill(viewerLeft + 1, overlayY, viewerRight - 1, viewerBottom - 1, 0x88000000);
            String pauseHint = "\u25B6 Press Play";
            int hintW = this.textRenderer.getWidth(pauseHint);
            int hintX = viewerLeft + (viewerRight - viewerLeft - hintW) / 2;
            RenderHelper.drawText(context, this.textRenderer, pauseHint, hintX, overlayY + 3, colors.textMuted);
        }

        progressLeft = viewerLeft + 10;
        progressTop = viewerBottom + 8;
        progressWidth = Math.max(120, (viewerRight - viewerLeft) - 20);
        progressHeight = 10;

        double ratio = durationSeconds > 0D ? Math.max(0D, Math.min(1D, currentSeconds / durationSeconds)) : 0D;
        drawOwoProgressBar(context, progressLeft, progressTop, progressWidth, progressHeight, ratio, colors);

        String name = videoFile.getName();
        int nameMax = viewerRight - viewerLeft - 20;
        if (this.textRenderer.getWidth(name) > nameMax) {
            name = this.textRenderer.trimToWidth(name, Math.max(8, nameMax - this.textRenderer.getWidth("..."))) + "...";
        }
        String timeLine = formatTime(currentSeconds) + " / " + formatTime(durationSeconds);
        String stateLine = "State: " + (playing ? "Playing" : "Paused") + "  |  Speed: x"
                + String.format(Locale.ROOT, "%.1f", SPEEDS[speedIndex]) + "  |  Volume: " + volumePercent + "%";
        int centerX = viewerLeft + (viewerRight - viewerLeft) / 2;
        drawOwoStatusChip(context, centerX, progressTop - 13, playing ? "PLAYING" : "PAUSED", colors);
        int timeW = this.textRenderer.getWidth(timeLine);
        int nameW = this.textRenderer.getWidth(name);
        int stateW = this.textRenderer.getWidth(stateLine);
        int infoW = this.textRenderer.getWidth(videoInfo);
        RenderHelper.drawText(context, this.textRenderer, timeLine, centerX - (timeW / 2), progressTop + 14, colors.textMuted);
        RenderHelper.drawText(context, this.textRenderer, name, centerX - (nameW / 2), progressTop + 26, colors.textPrimary);
        RenderHelper.drawText(context, this.textRenderer, videoInfo, centerX - (infoW / 2), progressTop + 38, colors.textMuted);
        RenderHelper.drawText(context, this.textRenderer, stateLine, centerX - (stateW / 2), progressTop + 50, colors.textSecondary);

        if (errorMessage != null && !errorMessage.isBlank()) {
            RenderHelper.drawText(context, this.textRenderer, errorMessage, viewerLeft, bottom - 18, 0xFFFF6666);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPreviewBackground(DrawContext context) {
        context.fill(0, 0, this.width, this.height, 0xFF1A1A1A);
        context.fill(0, 0, this.width, this.height, 0x66000000);
    }

    private void drawOwoProgressBar(DrawContext context, int x, int y, int width, int height, double ratio, ThemeColors colors) {
        int track = Color.ofArgb(colors.sectionBackground).interpolate(Color.ofArgb(colors.panelBackgroundAlt), 0.45f).argb();
        int border = Color.ofArgb(colors.panelBorder).interpolate(Color.ofArgb(colors.headerText), 0.12f).argb();
        int fillStart = Color.ofArgb(colors.accentDim).interpolate(Color.ofArgb(colors.accent), 0.55f).argb();
        int fillEnd = Color.ofArgb(colors.accent).interpolate(Color.ofArgb(colors.accentHover), 0.40f).argb();
        context.fill(x, y, x + width, y + height, track);
        int fillW = (int) (width * ratio);
        if (fillW > 0) {
            context.fill(x, y, x + fillW, y + height, fillStart);
            int glossH = Math.max(1, height / 3);
            context.fill(x, y, x + fillW, y + glossH, fillEnd);
        }
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
    }

    private void drawOwoStatusChip(DrawContext context, int centerX, int y, String label, ThemeColors colors) {
        int textW = this.textRenderer.getWidth(label);
        int chipW = textW + 14;
        int chipLeft = centerX - (chipW / 2);
        int chipRight = chipLeft + chipW;
        int chipBg = Color.ofArgb(colors.panelBackground).interpolate(Color.ofArgb(colors.sectionBackground), 0.55f).argb();
        int chipEdge = Color.ofArgb(colors.accent).interpolate(Color.ofArgb(colors.panelBorder), 0.45f).argb();
        context.fill(chipLeft, y, chipRight, y + 11, chipBg);
        context.fill(chipLeft, y, chipRight, y + 1, chipEdge);
        RenderHelper.drawText(context, this.textRenderer, label, centerX - (textW / 2), y + 2, colors.textPrimary);
    }

    private static String formatTime(double seconds) {
        if (seconds <= 0D || Double.isNaN(seconds) || Double.isInfinite(seconds)) return "--:--";
        int total = (int) Math.max(0, Math.round(seconds));
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    @Override
    public void close() {
        stopProcess();
        releaseFrameTexture();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void openInDefaultPlayer() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> command = new ArrayList<>();
            if (os.contains("win")) {
                command.add("explorer.exe");
                command.add(videoFile.getAbsolutePath());
            } else if (os.contains("mac")) {
                command.add("open");
                command.add(videoFile.getAbsolutePath());
            } else {
                command.add("xdg-open");
                command.add(videoFile.getAbsolutePath());
            }
            new ProcessBuilder(command).start();
            errorMessage = null;
        } catch (IOException e) {
            errorMessage = "Failed to open external player: " + e.getMessage();
        }
    }

    private void openRecordingsFolder() {
        try {
            File folder = videoFile.getParentFile();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("explorer", folder.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", folder.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", folder.getAbsolutePath()).start();
            }
            errorMessage = null;
        } catch (IOException e) {
            errorMessage = "Failed to open folder: " + e.getMessage();
        }
    }

    private static String resolveSiblingExecutable(String ffmpegPath, String targetBaseName) {
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            return targetBaseName;
        }
        String normalizedFfmpegPath = sanitizeExecutablePath(ffmpegPath);
        String lower = normalizedFfmpegPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ffmpeg.exe")) {
            return normalizedFfmpegPath.substring(0, normalizedFfmpegPath.length() - "ffmpeg.exe".length()) + targetBaseName + ".exe";
        }
        if (lower.endsWith("ffmpeg")) {
            return normalizedFfmpegPath.substring(0, normalizedFfmpegPath.length() - "ffmpeg".length()) + targetBaseName;
        }
        return targetBaseName;
    }

    private static String sanitizeExecutablePath(String rawPath) {
        String sanitized = rawPath == null ? "" : rawPath.trim();
        boolean changed = true;
        while (changed && sanitized.length() >= 2) {
            changed = false;
            if ((sanitized.startsWith("\"") && sanitized.endsWith("\""))
                    || (sanitized.startsWith("'") && sanitized.endsWith("'"))
                    || (sanitized.startsWith("(") && sanitized.endsWith(")"))) {
                sanitized = sanitized.substring(1, sanitized.length() - 1).trim();
                changed = true;
            }
        }
            return sanitized;
    }
}
