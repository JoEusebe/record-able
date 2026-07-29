package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.AudioCapture;
import dev.recordable.DiskSpaceGuardian;
import dev.recordable.FFmpegEncoder;
import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.NativeFolderPicker;
import dev.recordable.RecordingManager;
import dev.recordable.theme.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import dev.recordable.theme.CycleButton;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
// 1.20.4: keyPressed uses (int, int, int) not KeyInput
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Responsive in-game settings screen with scrolling support. */
public final class RecordableSettingsScreen extends Screen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 22;
    private static final Text WINDOWS_AUDIO_WARNING_TEXT = Text.literal("\u2139 Audio capture uses DirectShow (Stereo Mix) to record game audio - same approach as OBS Studio. Enable Stereo Mix in Sound settings if not detected.");
    private static final Text LINUX_AUDIO_INFO_TEXT = Text.literal("\u2139 Audio capture uses PulseAudio monitor source to record system audio directly.");
    private static final Text MACOS_AUDIO_INFO_TEXT = Text.literal("\u2139 Audio capture uses AVFoundation. Install BlackHole for system audio capture.");
    private static final Text ANDROID_AUDIO_INFO_TEXT = Text.literal("\u2139 Audio is captured directly from Minecraft's sound engine via OpenAL loopback. Full-volume game audio with zero noise.");
    private static final Text STEREO_MIX_HELP_TEXT = Text.literal("Audio is captured via system loopback (Stereo Mix/PulseAudio). If no audio, enable Stereo Mix in Windows Sound settings.");

    private final Screen parent;
    private final List<LayoutWidget> layoutWidgets = new ArrayList<>();

    private Text statusMessage;
    private Text ffmpegStatus;
    private boolean ffmpegStatusIsError;
    private boolean statusIsError;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int panelBodyTop;
    private int panelBodyBottom;
    private int footerY;

    private int contentHeight;
    private int fullContentHeight;
    private int scrollOffset;
    private boolean draggingScrollbar = false;

    // Feature search
    private TextFieldWidget searchBox;
    private String searchQuery = "";
    private int searchMatchRows;
    private int videoHeaderY;
    private int audioHeaderY;
    private int generalHeaderY;
    private int autoRecordHeaderY;
    private int appearanceHeaderY;
    private int positionsHeaderY;
    private int performanceHeaderY;
    private int advancedHeaderY;
    private int filenamePatternLabelY;
    private int bitrateLabelY;
    private int performanceHintY;
    private int outputLabelY;
    private int outputPathY;
    private int ffmpegStatusY;
    private int diskSpaceInfoY;
    private int autoClipHeaderY;
    private int androidHeaderY;
    private int v07HeaderY;
    private int chatNotifyHeaderY;
    private int compatHeaderY;
    private int deferredHeaderY;
    private int windowsAudioWarningY;
    private int windowsAudioWarningHeight;

    public RecordableSettingsScreen(Screen parent) {
        super(Text.translatable("screen.recordable.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearChildren();
        this.layoutWidgets.clear();
        this.statusMessage = null;
        this.statusIsError = false;

        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null) {
                this.statusMessage = Text.literal("Record-able config is unavailable.");
                this.statusIsError = true;
                addFallbackCloseButton();
                return;
            }
            config.sanitize();

            FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
            String platformLabel = PlatformUtils.detectPlatform().displayName();
            this.ffmpegStatus = Text.literal("Platform: " + platformLabel + " | FFmpeg: " + status.displayText());
            this.ffmpegStatusIsError = !status.found();

            this.panelWidth = Math.max(340, Math.min((int) (this.width * 0.78D), 760));
            this.panelLeft = (this.width - this.panelWidth) / 2;
            this.panelTop = Math.max(8, (int) (this.height * 0.04D));
            this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(300, (int) (this.height * 0.90D)));
            // Leave room for the fixed (non-scrolling) feature search bar between the
            // title and the scrolling body so they never overlap.
            this.panelBodyTop = this.panelTop + 46;
            this.footerY = this.panelBottom - 28;
            this.panelBodyBottom = this.footerY - 8;

            int widgetWidth = Math.max(180, this.panelWidth - 28);
            int widgetLeft = this.panelLeft + 14;
            int halfWidgetWidth = Math.max(88, (widgetWidth - 6) / 2);
            int rightWidgetLeft = widgetLeft + halfWidgetWidth + 6;

            // Feature search bar (fixed position, does not scroll with the body).
            this.searchBox = new TextFieldWidget(this.textRenderer, widgetLeft, this.panelTop + 24, widgetWidth, 18,
                    Text.translatable("screen.recordable.settings.search"));
            this.searchBox.setMaxLength(64);
            this.searchBox.setPlaceholder(Text.translatable("screen.recordable.settings.search"));
            this.searchBox.setText(this.searchQuery);
            this.searchBox.setChangedListener(value -> {
                this.searchQuery = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
                this.scrollOffset = 0;
                updateWidgetLayout();
            });
            this.addDrawableChild(this.searchBox);

            int y = 0;

            // Device performance preset moved to the Performance category (V1-0.08).

            addLayoutWidget(ButtonWidget.builder(
                            Text.translatable("screen.recordable.settings.open_video_collection"),
                            button -> {
                                if (this.client != null) {
                                    this.client.setScreen(new VideoCollectionScreen(this));
                                }
                            }
                    ).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT).build(),
                    y);
            y += ROW_SPACING;

            // Rename File Name: naming pattern used for new manual recordings.
            this.filenamePatternLabelY = y;
            y += 12;
            TextFieldWidget filenamePatternField = new TextFieldWidget(this.textRenderer, widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    Text.literal("Rename File Name"));
            filenamePatternField.setMaxLength(128);
            filenamePatternField.setText(config.filenamePattern == null || config.filenamePattern.isBlank()
                    ? RecordableConfig.DEFAULT_FILENAME_PATTERN : config.filenamePattern);
            filenamePatternField.setPlaceholder(Text.literal(RecordableConfig.DEFAULT_FILENAME_PATTERN));
            filenamePatternField.setChangedListener(value -> {
                config.filenamePattern = value == null || value.isBlank()
                        ? RecordableConfig.DEFAULT_FILENAME_PATTERN : value.trim();
                saveConfigSafely(config);
            });
            filenamePatternField.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Name for new recordings. Place these tokens where you want them:\n\n"
                                    + "{datetime} -> 20250101-134501\n"
                                    + "{date} -> 20250101\n"
                                    + "{time} -> 134501\n\n"
                                    + "Example: myclip-{datetime}")));
            addLayoutWidget(filenamePatternField, y, "rename file name filename pattern");
            y += WIDGET_HEIGHT + 2;

            this.videoHeaderY = y;
            y += 12;
            CycleButton formatButton = CycleButton.create(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    Text.literal("Output: ." + config.getFormat()),
                    button -> {
                        String next = nextValue(RecordableConfig.FORMATS, config.getFormat());
                        config.format = next;
                        saveConfigSafely(config);
                        button.setMessage(Text.literal("Output: ." + config.getFormat()));
                    },
                    button -> {
                        String prev = prevValue(RecordableConfig.FORMATS, config.getFormat());
                        config.format = prev;
                        saveConfigSafely(config);
                        button.setMessage(Text.literal("Output: ." + config.getFormat()));
                    });
            formatButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "MP4/MKV/MOV use H.264 (fast, hardware-accelerated, best on Android). WebM uses VP9: smaller files but CPU-heavy software encoding, recommended for desktop only.")));
            addLayoutWidget(formatButton, y);
            addLayoutWidget(addCycleButton(rightWidgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.resolution", RecordableConfig.RESOLUTIONS,
                    () -> config.resolution, value -> {
                        config.resolution = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            addLayoutWidget(addCycleButton(widgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.quality", RecordableConfig.QUALITIES,
                    () -> config.quality, value -> {
                        config.quality = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(new FpsSlider(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT, config.getFps(), value -> {
                config.fps = value;
                saveConfigSafely(config);
            }), y);
            y += ROW_SPACING;

            List<RecordableConfig.VideoEncoder> availableEncoders = FFmpegEncoder.detectAvailableEncoders();
            if (!availableEncoders.contains(config.encoder)) {
                config.encoder = RecordableConfig.VideoEncoder.SOFTWARE;
                saveConfigSafely(config);
            }
            CycleButton encoderButton = CycleButton.create(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    cycleEncoderMessage(config.encoder),
                    button -> {
                        RecordableConfig.VideoEncoder next = nextEncoder(availableEncoders, config.encoder);
                        config.encoder = next;
                        saveConfigSafely(config);
                        button.setMessage(cycleEncoderMessage(next));
                    },
                    button -> {
                        RecordableConfig.VideoEncoder prev = prevEncoder(availableEncoders, config.encoder);
                        config.encoder = prev;
                        saveConfigSafely(config);
                        button.setMessage(cycleEncoderMessage(prev));
                    });
            encoderButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Select video encoder backend")));
            addLayoutWidget(encoderButton, y);
            y += ROW_SPACING;

            {
                String encoderLabel = "Encoder: FFmpeg";
                if (!status.found()) {
                    encoderLabel += " (NOT FOUND)";
                }

                // If FFmpeg is not found, render the re-detect button half-width and
                // expose a "Download FFmpeg" button next to it. When it IS found, the
                // re-detect button gets the full width like before.
                if (!status.found()) {
                    addLayoutWidget(ButtonWidget.builder(
                            Text.literal(encoderLabel),
                            button -> {
                                FFmpegEncoder.FfmpegStatus refreshed = FFmpegEncoder.detectFfmpeg();
                                String label = "Encoder: FFmpeg" + (refreshed.found() ? " ✓" : " (NOT FOUND)");
                                button.setMessage(Text.literal(label));
                                if (!refreshed.found()) {
                                    this.statusMessage = Text.literal("§cFFmpeg is required. " + PlatformUtils.getFfmpegInstallHint());
                                    this.statusIsError = true;
                                } else {
                                    this.statusMessage = Text.literal("✓ FFmpeg detected: " + refreshed.version());
                                    this.statusIsError = false;
                                }
                            }
                    ).dimensions(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT)
                            .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                                    "FFmpeg is the sole encoder. Click to re-detect after installing/downloading.\n\n"
                                            + "FFmpeg status: " + status.displayText() + "\n"
                                            + "Install: " + PlatformUtils.getFfmpegInstallHint())))
                            .build(), y);

                    String dlLabel;
                    boolean autoOk = FfmpegBundleManager.isAutoDownloadSupported();
                    if (FfmpegBundleManager.isDownloading()) {
                        FfmpegBundleManager.DownloadProgress dp = FfmpegBundleManager.getLastProgress();
                        dlLabel = "Downloading… " + dp.displayPercent();
                    } else if (autoOk) {
                        dlLabel = "Download FFmpeg (" + FfmpegBundleManager.getEstimatedDownloadSize() + ")";
                    } else {
                        dlLabel = "FFmpeg Setup…";
                    }
                    String dlTip = autoOk
                            ? "Open the FFmpeg setup screen to download from "
                                    + FfmpegBundleManager.getDownloadSourceDescription()
                                    + ".\nFFmpeg is NOT bundled in this mod - first run downloads ~"
                                    + FfmpegBundleManager.getEstimatedDownloadSize()
                                    + " from a trusted upstream and verifies the hash."
                            : FfmpegBundleManager.getManualInstallInstructions();
                    addLayoutWidget(ButtonWidget.builder(
                            Text.literal(dlLabel),
                            button -> {
                                if (this.client != null) {
                                    this.client.setScreen(new FfmpegDownloadScreen(this));
                                }
                            }
                    ).dimensions(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT)
                            .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(dlTip)))
                            .build(), y);
                    y += ROW_SPACING;
                } else {
                    addLayoutWidget(ButtonWidget.builder(
                            Text.literal(encoderLabel + " ✓"),
                            button -> {
                                FFmpegEncoder.FfmpegStatus refreshed = FFmpegEncoder.detectFfmpeg();
                                String label = "Encoder: FFmpeg" + (refreshed.found() ? " ✓" : " (NOT FOUND)");
                                button.setMessage(Text.literal(label));
                                if (!refreshed.found()) {
                                    this.statusMessage = Text.literal("§cFFmpeg is required. " + PlatformUtils.getFfmpegInstallHint());
                                    this.statusIsError = true;
                                } else {
                                    this.statusMessage = Text.literal("✓ FFmpeg detected: " + refreshed.version());
                                    this.statusIsError = false;
                                }
                            }
                    ).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                            .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                                    "FFmpeg is the sole encoder. Click to re-detect.\n\n"
                                            + "FFmpeg status: " + status.displayText() + "\n"
                                            + "Audio: System loopback (DirectShow/PulseAudio/AVFoundation)\n\n"
                                            + "Install FFmpeg: " + PlatformUtils.getFfmpegInstallHint())))
                            .build(), y);
                    y += ROW_SPACING;
                }
            }

            TextFieldWidget bitrateField = new TextFieldWidget(this.textRenderer, widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    Text.translatable("screen.recordable.settings.bitrate"));
            bitrateField.setMaxLength(16);
            bitrateField.setText(config.bitrate == null ? "auto" : config.bitrate);
            bitrateField.setChangedListener(value -> {
                config.bitrate = value == null || value.isBlank() ? "auto" : value.trim();
                saveConfigSafely(config);
            });
            addLayoutWidget(bitrateField, y);
            y += WIDGET_HEIGHT + 2;

            this.bitrateLabelY = y;
            y += 12;

            this.performanceHintY = y;
            y += 22;

            this.audioHeaderY = y;
            y += 12;
            ClickableWidget captureAudioToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.capture_audio", config.captureAudio, value -> {
                config.captureAudio = value;
                saveConfigSafely(config);
            });
            captureAudioToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.capture_audio_hint")));
            addLayoutWidget(captureAudioToggle, y);

            TextFieldWidget audioDeviceField = new TextFieldWidget(this.textRenderer, widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    Text.literal("Audio Device"));
            audioDeviceField.setMaxLength(128);
            audioDeviceField.setText(config.audioDevice == null ? "auto" : config.audioDevice);
            audioDeviceField.setPlaceholder(Text.literal("auto"));
            audioDeviceField.setChangedListener(value -> {
                config.audioDevice = value == null || value.isBlank() ? "auto" : value.trim();
                saveConfigSafely(config);
                AudioCapture.clearCache();
            });

            FFmpegEncoder.FfmpegStatus ffStatus = FFmpegEncoder.detectFfmpeg();
            String ffExe = ffStatus.found() ? ffStatus.executable() : "ffmpeg";
            AudioCapture.AudioDeviceStatus initialAudioStatus = AudioCapture.detectAudioDevice(ffExe, config.audioDevice);
            boolean stereoMixDetected = initialAudioStatus.available();
            Text audioLabelText = audioStatusText(stereoMixDetected);
            String audioTooltip = stereoMixDetected
                    ? initialAudioStatus.message() + "\nClick to re-scan audio devices."
                    : initialAudioStatus.message() + "\n" + STEREO_MIX_HELP_TEXT.getString();

            addLayoutWidget(ButtonWidget.builder(
                            audioLabelText,
                            button -> {
                                AudioCapture.clearCache();
                                AudioCapture.AudioDeviceStatus refreshed = AudioCapture.detectAudioDevice(ffExe, "auto");
                                if (refreshed.available()) {
                                    config.audioDevice = refreshed.deviceName();
                                    saveConfigSafely(config);
                                    audioDeviceField.setText(refreshed.deviceName());
                                    button.setMessage(audioStatusText(true));
                                    button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                                            Text.literal(refreshed.message() + "\nClick to re-scan audio devices.")));
                                    this.statusMessage = Text.literal("\u2705 Stereo Mix detected. Audio recording is enabled.");
                                    this.statusIsError = false;
                                } else {
                                    button.setMessage(audioStatusText(false));
                                    button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                                            Text.literal(refreshed.message() + "\n" + STEREO_MIX_HELP_TEXT.getString())));
                                    this.statusMessage = Text.literal("\u26A0 Stereo Mix not detected. Video-only recording still works perfectly.");
                                    this.statusIsError = false;
                                    if (this.client != null && this.client.player != null) {
                                        this.client.player.sendMessage(
                                                Text.literal("⚠ Stereo Mix not found. Recording will continue in video-only mode."),
                                                false
                                        );
                                    }
                                }
                            }
                    ).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(audioTooltip)))
                    .dimensions(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT).build(),
                    y);
            y += ROW_SPACING;

            addLayoutWidget(audioDeviceField, y);
            y += ROW_SPACING;

            String audioContainer = config.getContainerFromFormat();
            List<RecordableConfig.AudioEncoder> detectedAudioEncoders = FFmpegEncoder.detectAvailableAudioEncoders();
            List<RecordableConfig.AudioEncoder> compatibleAudioEncoders = detectedAudioEncoders.stream()
                    .filter(encoder -> encoder.supportsContainer(audioContainer))
                    .toList();
            if (compatibleAudioEncoders.isEmpty()) {
                RecordableMod.LOGGER.warn("No detected audio encoders support container {}. Falling back to config enum values.", audioContainer);
                compatibleAudioEncoders = java.util.Arrays.stream(RecordableConfig.AudioEncoder.values())
                        .filter(encoder -> encoder.supportsContainer(audioContainer))
                        .toList();
            }
            if (compatibleAudioEncoders.isEmpty()) {
                compatibleAudioEncoders = List.of(RecordableConfig.AudioEncoder.AAC);
            }
            final List<RecordableConfig.AudioEncoder> availableAudioEncoders = compatibleAudioEncoders;
            RecordableMod.LOGGER.info("Audio encoder options for container {}: {}", audioContainer,
                    availableAudioEncoders.stream().map(encoder -> encoder.displayName).toList());
            if (!availableAudioEncoders.contains(config.audioEncoder)) {
                config.audioEncoder = availableAudioEncoders.get(0);
                saveConfigSafely(config);
            }
            // Audio settings: simplified to just volume control
            addLayoutWidget(new AudioVolumeSlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT, config.audioVolume, value -> {
                config.audioVolume = value;
                saveConfigSafely(config);
            }), y);
            y += ROW_SPACING;

            // === Microphone (second audio input, mixed with game audio) ===
            ClickableWidget captureMicToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.capture_microphone", config.captureMicrophone, value -> {
                        config.captureMicrophone = value;
                        saveConfigSafely(config);
                    });
            captureMicToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.capture_microphone_hint")));
            addLayoutWidget(captureMicToggle, y);

            // Microphone device selector: a button that cycles through the detected input
            // devices (plus "auto"). Replaces the old free-text field so users no longer have
            // to type exact device names. The device list is re-scanned on each click so newly
            // plugged microphones appear without reopening the screen.
            String currentMic = (config.microphoneDevice == null || config.microphoneDevice.isBlank())
                    ? "auto" : config.microphoneDevice.trim();
            java.util.List<String> initialMicOptions = buildMicDeviceOptions(ffExe, config.microphoneDevice);
            ButtonWidget micDeviceButton = ButtonWidget.builder(
                    micDeviceButtonLabel(currentMic),
                    button -> {
                        java.util.List<String> opts = buildMicDeviceOptions(ffExe, config.microphoneDevice);
                        String cur = (config.microphoneDevice == null || config.microphoneDevice.isBlank())
                                ? "auto" : config.microphoneDevice.trim();
                        int idx = opts.indexOf(cur);
                        if (idx < 0) {
                            idx = 0;
                        }
                        String chosen = opts.get((idx + 1) % opts.size());
                        config.microphoneDevice = chosen;
                        saveConfigSafely(config);
                        AudioCapture.clearCache();
                        button.setMessage(micDeviceButtonLabel(chosen));
                        button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                                micDeviceTooltip(opts, chosen)));
                    })
                    .dimensions(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            micDeviceTooltip(initialMicOptions, currentMic)))
                    .build();
            addLayoutWidget(micDeviceButton, y);
            y += ROW_SPACING;

            addLayoutWidget(new MixVolumeSlider(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    "Game Volume", config.gameAudioVolume, value -> {
                        config.gameAudioVolume = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(new MixVolumeSlider(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    "Mic Volume", config.microphoneVolume, value -> {
                        config.microphoneVolume = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // Push-to-Talk: when ON, the mic is only recorded while the PTT key is held.
            ClickableWidget pttToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.push_to_talk", config.microphonePushToTalk, value -> {
                        config.microphonePushToTalk = value;
                        saveConfigSafely(config);
                    });
            pttToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.push_to_talk_hint")));
            addLayoutWidget(pttToggle, y);

            // Noise Suppression (OBS-style): FFT denoiser applied to the mic during capture.
            ClickableWidget noiseToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.noise_suppression", config.noiseSuppression, value -> {
                        config.noiseSuppression = value;
                        saveConfigSafely(config);
                    });
            noiseToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.noise_suppression_hint")));
            addLayoutWidget(noiseToggle, y);
            y += ROW_SPACING;

            // Test Mic: records a short sample from the selected device and reports its level,
            // so the user can confirm the mic actually delivers sound (and isn't blocked/muted
            // by the OS) before recording.
            ButtonWidget testMicButton = ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.test_mic"),
                    button -> runMicTest(button, ffExe))
                    .dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.translatable("screen.recordable.settings.test_mic_hint")))
                    .build();
            addLayoutWidget(testMicButton, y);
            y += ROW_SPACING;

            // === Audio Delay Preset ===
            CycleButton audioDelayButton = CycleButton.create(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    audioDelayPresetMessage(config.audioDelayPreset, config.getEffectiveAudioDelay()),
                    button -> {
                        config.audioDelayPreset = config.audioDelayPreset.next();
                        saveConfigSafely(config);
                        if (this.client != null) {
                            this.client.setScreen(new RecordableSettingsScreen(this.parent));
                        }
                    },
                    button -> {
                        config.audioDelayPreset = config.audioDelayPreset.previous();
                        saveConfigSafely(config);
                        if (this.client != null) {
                            this.client.setScreen(new RecordableSettingsScreen(this.parent));
                        }
                    });
            audioDelayButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Fine-tune audio sync if needed. Usually not required.\n\n"
                                    + "• Auto: 0ms (recommended, sync is handled automatically)\n"
                                    + "• None: 0ms (same as Auto, explicit zero)\n"
                                    + "• Desktop: 46ms (legacy, for unusual audio drivers)\n"
                                    + "• Android: 60ms (legacy, for mobile latency)\n"
                                    + "• Custom: Set your own value with the slider below\n\n"
                                    + "If audio is ahead of video, increase the delay.\n"
                                    + "If audio is behind video, decrease the delay.")));
            addLayoutWidget(audioDelayButton, y);
            y += ROW_SPACING;

            // Custom delay slider (only active when CUSTOM preset is selected)
            AudioDelaySlider audioDelaySlider = new AudioDelaySlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    config.audioSyncOffsetMs, value -> {
                config.audioSyncOffsetMs = value;
                saveConfigSafely(config);
            });
            audioDelaySlider.active = config.audioDelayPreset == RecordableConfig.AudioDelayPreset.CUSTOM;
            addLayoutWidget(audioDelaySlider, y);
            y += ROW_SPACING + 4;

            this.windowsAudioWarningY = y;
            int warningWrapWidth = Math.max(160, this.panelWidth - 28);
            Text platformWarningText = getPlatformAudioWarningText();
            if (platformWarningText != null) {
                int warningLineCount = Math.max(1, this.textRenderer.wrapLines(platformWarningText, warningWrapWidth).size());
                int lineHeight = this.textRenderer.fontHeight + 1;
                this.windowsAudioWarningHeight = warningLineCount * lineHeight + 4;
            } else {
                this.windowsAudioWarningHeight = 0;
            }
            y += this.windowsAudioWarningHeight + 6;

            this.generalHeaderY = y;
            y += 12;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.enabled", config.enabled, value -> {
                config.enabled = value;
                saveConfigSafely(config);
            }), y);
            ClickableWidget showOverlayToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.overlay", config.showOverlay, value -> {
                config.showOverlay = value;
                saveConfigSafely(config);
            });
            showOverlayToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                    "Shows or hides the recording info overlay (REC timer, FPS, file size) on YOUR screen while recording. "
                            + "To control whether the overlay is saved into the video file, use the \"Bake in Overlay\" option in Streamer Mode.")));
            addLayoutWidget(showOverlayToggle, y);
            y += ROW_SPACING;

            ClickableWidget showMousePointerToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.show_mouse_pointer", config.showMousePointer, value -> {
                        config.showMousePointer = value;
                        saveConfigSafely(config);
                    });
            showMousePointerToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.show_mouse_pointer.tooltip")));
            addLayoutWidget(showMousePointerToggle, y);
            CycleButton pointerThemeButton = CycleButton.create(
                    rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    Text.translatable("screen.recordable.settings.pointer_theme", config.mousePointerTheme.displayName),
                    button -> {
                        config.mousePointerTheme = config.mousePointerTheme.next();
                        saveConfigSafely(config);
                        button.setMessage(Text.translatable("screen.recordable.settings.pointer_theme",
                                config.mousePointerTheme.displayName));
                    },
                    button -> {
                        config.mousePointerTheme = config.mousePointerTheme.previous();
                        saveConfigSafely(config);
                        button.setMessage(Text.translatable("screen.recordable.settings.pointer_theme",
                                config.mousePointerTheme.displayName));
                    });
            pointerThemeButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.pointer_theme.tooltip")));
            addLayoutWidget(pointerThemeButton, y);
            y += ROW_SPACING;
            addLayoutWidget(new MousePointerScaleSlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    config.mousePointerScale, value -> {
                        config.mousePointerScale = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            ClickableWidget stopOnDisconnectToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.stop_on_disconnect", config.stopOnDisconnect, value -> {
                        config.stopOnDisconnect = value;
                        saveConfigSafely(config);
                    });
            stopOnDisconnectToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.stop_on_disconnect.tooltip")));
            addLayoutWidget(stopOnDisconnectToggle, y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.show_home_button", config.showHomeButton, value -> {
                config.showHomeButton = value;
                saveConfigSafely(config);
            }), y);
            y += ROW_SPACING;

            // Performance stats HUD toggle moved to the Performance category (V1-0.08).

            // === Overlay Position & Scale (in General section for discoverability) ===
            CycleButton overlayPositionButton = CycleButton.create(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    Text.literal("Overlay Position: " + config.overlayPosition.displayName),
                    button -> {
                        config.overlayPosition = config.overlayPosition.next();
                        saveConfigSafely(config);
                        button.setMessage(Text.literal("Overlay Position: " + config.overlayPosition.displayName));
                    },
                    button -> {
                        config.overlayPosition = config.overlayPosition.previous();
                        saveConfigSafely(config);
                        button.setMessage(Text.literal("Overlay Position: " + config.overlayPosition.displayName));
                    });
            overlayPositionButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Where to place the recording overlay on screen.\n\n"
                                    + "- Top-Left: Classic position\n"
                                    + "- Top-Right: Right side\n"
                                    + "- Bottom-Left: Lower left\n"
                                    + "- Bottom-Right: Lower right\n"
                                    + "- Center-Top: Centered, below boss bars")));
            addLayoutWidget(overlayPositionButton, y);
            addLayoutWidget(new OverlayScaleSlider(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT, config.overlayScale, value -> {
                config.overlayScale = value;
                saveConfigSafely(config);
            }), y);
            y += ROW_SPACING;

            // Recordings folder: text field for the current path plus a "Browse" button
            // that opens the native OS folder picker so it can be moved anywhere. On
            // Android (no native dialog) only the text field is shown, full width.
            boolean canBrowse = NativeFolderPicker.isSupported();
            int browseW = 62;
            int dirFieldWidth = canBrowse ? widgetWidth - browseW - 6 : widgetWidth;
            final TextFieldWidget outputDirField = new TextFieldWidget(this.textRenderer, widgetLeft, 0,
                    dirFieldWidth, WIDGET_HEIGHT, Text.translatable("screen.recordable.settings.output_dir"));
            outputDirField.setMaxLength(256);
            outputDirField.setText(config.outputDir == null ? "recordings" : config.outputDir);
            outputDirField.setChangedListener(value -> {
                config.outputDir = value == null || value.isBlank() ? "recordings" : value.trim();
                saveConfigSafely(config);
            });
            addLayoutWidget(outputDirField, y);
            if (canBrowse) {
                ButtonWidget browseButton = ButtonWidget.builder(
                        Text.translatable("screen.recordable.settings.browse"),
                        button -> chooseOutputFolder(outputDirField))
                        .dimensions(widgetLeft + dirFieldWidth + 6, 0, browseW, WIDGET_HEIGHT)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                                Text.literal("Pick a folder anywhere on your PC to save recordings.")))
                        .build();
                addLayoutWidget(browseButton, y);
            }
            y += WIDGET_HEIGHT + 2;

            this.outputLabelY = y;
            y += 10;

            this.outputPathY = y;
            y += 12;

            addLayoutWidget(new MaxFileSizeSlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT, config.maxFileSizeMB, value -> {
                config.maxFileSizeMB = value;
                saveConfigSafely(config);
            }), y);
            y += ROW_SPACING + 2;

            // === Android Section (only shown on Android devices) ===
            if (PlatformUtils.isAndroid()) {
                this.androidHeaderY = y;
                y += 12;

                ClickableWidget galleryToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                        "screen.recordable.settings.save_to_gallery", config.saveToGalleryOnAndroid, value -> {
                            config.saveToGalleryOnAndroid = value;
                            saveConfigSafely(config);
                        });
                galleryToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                        Text.translatable("screen.recordable.settings.save_to_gallery.tooltip")));

                ClickableWidget compressToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                        "screen.recordable.settings.auto_compress", config.autoCompressOnAndroid, value -> {
                            config.autoCompressOnAndroid = value;
                            saveConfigSafely(config);
                        });
                compressToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                        Text.translatable("screen.recordable.settings.auto_compress.tooltip")));

                addLayoutWidget(galleryToggle, y);
                addLayoutWidget(compressToggle, y);
                y += ROW_SPACING + 2;
            }

            this.autoRecordHeaderY = y;
            y += 12;
            ClickableWidget autoRecordToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.auto_record.enabled", config.autoRecord, value -> {
                config.autoRecord = value;
                saveConfigSafely(config);
            });
            autoRecordToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.literal("Master switch for automatic recording.\n"
                            + "§7Starts and stops based on the Start/Stop triggers below\n"
                            + "§7(e.g. record on World Join, stop on World Leave).\n"
                            + "§7Set Start Trigger to Manual for hotkey-only recording.")));
            addLayoutWidget(autoRecordToggle, y);
            addLayoutWidget(addCycleButton(rightWidgetLeft, 0, halfWidgetWidth, "screen.recordable.settings.auto_record.start_trigger", RecordableConfig.AUTO_RECORD_TRIGGERS,
                    () -> config.autoRecordTrigger, value -> {
                        config.autoRecordTrigger = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            addLayoutWidget(addCycleButton(widgetLeft, 0, widgetWidth, "screen.recordable.settings.auto_record.stop_trigger", RecordableConfig.AUTO_STOP_TRIGGERS,
                    () -> config.autoStopTrigger, value -> {
                        config.autoStopTrigger = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING + 2;

            this.appearanceHeaderY = y;
            y += 20;
            addLayoutWidget(new ColorPickerWidget(
                    this.textRenderer,
                    widgetLeft,
                    0,
                    widgetWidth,
                    ColorPickerWidget.WIDGET_HEIGHT,
                    Text.translatable("screen.recordable.settings.overlay_color"),
                    config.overlayColor,
                    value -> {
                        config.overlayColor = value;
                        saveConfigSafely(config);
                    }
            ), y);
            y += 24;
            addLayoutWidget(new ColorPickerWidget(
                    this.textRenderer,
                    widgetLeft,
                    0,
                    widgetWidth,
                    ColorPickerWidget.WIDGET_HEIGHT,
                    Text.translatable("screen.recordable.settings.menu_accent_color"),
                    config.menuAccentColor,
                    value -> {
                        config.menuAccentColor = value;
                        saveConfigSafely(config);
                    }
            ), y);
            y += 24;

            // Overlay Position & Scale are now in the General section above for easier access
            y += 6;

            // === On-Screen Overlay Style ===
            CycleButton overlayStyleButton = CycleButton.create(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    Text.literal("Overlay Style: " + config.overlayStyleHud.displayName),
                    button -> {
                        config.overlayStyleHud = config.overlayStyleHud.next();
                        saveConfigSafely(config);
                        // Rebuild entire screen so conditional VHS controls appear/disappear
                        int savedScroll = this.scrollOffset;
                        this.init();
                        this.scrollOffset = savedScroll;
                    },
                    button -> {
                        config.overlayStyleHud = config.overlayStyleHud.previous();
                        saveConfigSafely(config);
                        // Rebuild entire screen so conditional VHS controls appear/disappear
                        int savedScroll = this.scrollOffset;
                        this.init();
                        this.scrollOffset = savedScroll;
                    });
            overlayStyleButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("On-screen overlay visible while recording.\nSpeed-Runner's Classic = info panel. VHS = camcorder look. None = hidden.")));
            addLayoutWidget(overlayStyleButton, y);
            y += ROW_SPACING;

            // === Overlay Skin (follows the selected UI Theme) ===
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("Overlay Skin: " + (config.overlaySkinEnabled ? config.uiTheme.displayName : "Off")),
                    button -> {
                        config.overlaySkinEnabled = !config.overlaySkinEnabled;
                        saveConfigSafely(config);
                        button.setMessage(Text.literal("Overlay Skin: "
                                + (config.overlaySkinEnabled ? config.uiTheme.displayName : "Off")));
                    })
                    .dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Skins the on-screen overlay with the colors of your selected UI Theme.\nOn = overlay follows the UI Theme. Off = overlay uses its own default colors.")))
                    .build(), y);
            y += ROW_SPACING;

            // === VHS Detail Toggles (conditional on overlay style) ===
            // NOTE: VHS element colors are now edited in the Overlay Position Editor
            //       (accessible via "Position Elements..." button below)
            // Unified VHS: all detail toggles are available whenever the VHS style is active.
            RecordableConfig.OverlayStyleHud style = config.overlayStyleHud;
            boolean isVhs = style == RecordableConfig.OverlayStyleHud.VHS;
            boolean showBrackets  = isVhs;
            boolean showPlay      = isVhs;
            boolean showDate      = isVhs;
            boolean showSp        = isVhs;

            if (showBrackets || showPlay) {
                if (showBrackets) {
                    addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_brackets", config.vhsShowBrackets, v -> {
                                config.vhsShowBrackets = v; saveConfigSafely(config);
                            }), y);
                }
                if (showPlay) {
                    addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_play", config.vhsShowPlay, v -> {
                                config.vhsShowPlay = v; saveConfigSafely(config);
                            }), y);
                }
                y += ROW_SPACING;
            }

            if (showDate || showSp) {
                if (showDate) {
                    addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_date", config.vhsShowDate, v -> {
                                config.vhsShowDate = v; saveConfigSafely(config);
                            }), y);
                }
                if (showSp) {
                    addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_sp", config.vhsShowSp, v -> {
                                config.vhsShowSp = v; saveConfigSafely(config);
                            }), y);
                }
                y += ROW_SPACING;
            }

            boolean showBattery   = isVhs;
            boolean showAudioM    = isVhs;
            boolean showTapeC     = isVhs;

            if (showBattery || showAudioM) {
                if (showBattery) {
                    addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_battery", config.vhsShowBattery, v -> {
                                config.vhsShowBattery = v; saveConfigSafely(config);
                            }), y);
                }
                if (showAudioM) {
                    addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                            "screen.recordable.settings.vhs_audio_meter", config.vhsShowAudioMeter, v -> {
                                config.vhsShowAudioMeter = v; saveConfigSafely(config);
                            }), y);
                }
                y += ROW_SPACING;
            }

            if (showTapeC) {
                addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                        "screen.recordable.settings.vhs_tape_counter", config.vhsShowTapeCounter, v -> {
                            config.vhsShowTapeCounter = v; saveConfigSafely(config);
                        }), y);
                y += ROW_SPACING;
            }

            y += 4;

            // === UI Theme Button ===
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("🎨 UI Theme: " + config.uiTheme.displayName),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new ThemeSettingsScreen(this));
                        }
                    }
            ).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Customize the mod's visual theme.\n"
                                    + "Choose between VHS retro, Cinema film, Neon synthwave, and more.\n"
                                    + "Toggle scanlines, film grain, glitch effects and animations.")))
                    .build(), y);
            y += ROW_SPACING + 4;

            // === Element Positions Section ===
            this.positionsHeaderY = y;
            y += 14;

            // Visual drag-and-drop position editor button (also contains color pickers)
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("✎ Position & Colors Editor"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new OverlayPositionScreen(this));
                        }
                    }
            ).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Open the visual editor to reposition overlay elements and customize colors.\n\n"
                                    + "• Drag elements to move them\n"
                                    + "• Drag corner handles on Brackets to resize\n"
                                    + "• Right-click to reset an element\n"
                                    + "• Color panel on the right for per-element colors\n"
                                    + "• ESC to cancel all changes")))
                    .build(), y);
            y += ROW_SPACING;

            // Watermark editor button (grouped with the Position & Colors editor)
            addLayoutWidget(ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.open_watermarks"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new WatermarkScreen(this));
                        }
                    }).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT).build(), y);
            y += ROW_SPACING;

            // Streamer Mode editor (V1-0.08): censor regions + smooth motion + perf
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("\u25C9 Streamer Mode"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new StreamerModeScreen(this));
                        }
                    }).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Hide sensitive areas with censor boxes and enable smooth-motion recording.")))
                    .build(), y);
            y += ROW_SPACING;

            // === Performance Section (V1-0.08): its own top-level category ===
            this.performanceHeaderY = y;
            y += 14;

            // Performance category (V1-0.08): all performance features in one place.
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("\u26A1 Performance"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new PerformanceScreen(this));
                        }
                    }).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Device presets, performance optimizer, smooth motion, frame pooling, FPS targets and performance stats - all in one place.")))
                    .build(), y, "performance optimizer device preset smooth motion frame pooling fps stats");
            y += ROW_SPACING;

            // Capture Test / Diagnostics (V1-0.08): screen-size aware detector,
            // one-shot capture self-test and live black-frame health report.
            addLayoutWidget(ButtonWidget.builder(
                    Text.literal("\uD83D\uDD0D Capture Test"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new CaptureDiagnosticsScreen(this));
                        }
                    }).dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal("Runs a capture self-test and checks for the problems that cause black or blank recordings: framebuffer size mismatches, empty frames and a stuck capture source.")))
                    .build(), y, "capture test diagnostics black screen blank frame size detector self test health");
            y += ROW_SPACING + 4;

            // === Advanced Features Section ===
            this.advancedHeaderY = y;
            y += 12;

            // Feature 6: Toast Notification toggle
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.show_toast", config.showPostRecordingToast, value -> {
                        config.showPostRecordingToast = value;
                        saveConfigSafely(config);
                    }), y);

            // Feature 8: Recording Timer toggle
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.show_timer", config.showRecordingTimer, value -> {
                        config.showRecordingTimer = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // Feature 8: Show estimated file size
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.show_est_size", config.showEstimatedFileSize, value -> {
                        config.showEstimatedFileSize = value;
                        saveConfigSafely(config);
                    }), y);

            // Recording Bookmarks toggle
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.bookmarks", config.bookmarksEnabled, value -> {
                        config.bookmarksEnabled = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // Feature 1: Prompt to rename the recording after it is saved
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.prompt_rename", config.promptRenameAfterRecording, value -> {
                        config.promptRenameAfterRecording = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // === Auto-Clip Triggers Section ===
            this.autoClipHeaderY = y;
            y += 12;

            // Master toggle for the whole auto-clip feature
            ClickableWidget autoClipMasterToggle = addBooleanToggle(widgetLeft, 0, widgetWidth,
                    "screen.recordable.settings.autoclip_enabled", config.autoClipEnabled, value -> {
                        config.autoClipEnabled = value;
                        if (value) {
                            // Turning the feature on activates all triggers by default
                            config.autoClipOnAchievement = true;
                            config.autoClipOnDeath = true;
                            config.autoClipOnDimensionChange = true;
                            config.autoClipOnBossKill = true;
                            config.autoClipOnKill = true;
                            config.autoClipOnPlayerKill = true;
                            config.autoClipOnTotemPop = true;
                            config.autoClipOnCustomEvent = false;
                        } else {
                            // Turning the feature off deactivates all triggers
                            config.autoClipOnAchievement = false;
                            config.autoClipOnDeath = false;
                            config.autoClipOnDimensionChange = false;
                            config.autoClipOnBossKill = false;
                            config.autoClipOnKill = false;
                            config.autoClipOnPlayerKill = false;
                            config.autoClipOnTotemPop = false;
                            config.autoClipOnCustomEvent = false;
                        }
                        saveConfigSafely(config);
                        // Rebuild screen to update individual button states
                        init();
                    });
            autoClipMasterToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    net.minecraft.text.Text.literal(
                            "Automatically record short clips when specific events occur (achievements, deaths, boss kills, etc.)")));
            addLayoutWidget(autoClipMasterToggle, y);
            y += ROW_SPACING;

            // Individual trigger buttons (disabled when master toggle is OFF)
            ClickableWidget achievementToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_achievement", config.autoClipOnAchievement, value -> {
                        config.autoClipOnAchievement = value;
                        saveConfigSafely(config);
                    });
            achievementToggle.active = config.autoClipEnabled;
            addLayoutWidget(achievementToggle, y);

            ClickableWidget deathToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_death", config.autoClipOnDeath, value -> {
                        config.autoClipOnDeath = value;
                        saveConfigSafely(config);
                    });
            deathToggle.active = config.autoClipEnabled;
            addLayoutWidget(deathToggle, y);
            y += ROW_SPACING;

            ClickableWidget hindsightModeToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_hindsight_mode", config.autoClipHindsightEnabled, value -> {
                        config.autoClipHindsightEnabled = value;
                        saveConfigSafely(config);
                        init();
                    });
            hindsightModeToggle.active = config.autoClipEnabled;
            hindsightModeToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.autoclip_hindsight_mode.tooltip")));
            addLayoutWidget(hindsightModeToggle, y);

            ClickableWidget hindsightLookbackSlider = new AutoClipHindsightLookbackSlider(rightWidgetLeft, 0,
                    halfWidgetWidth, WIDGET_HEIGHT, config.autoClipHindsightLookbackSeconds, value -> {
                        config.autoClipHindsightLookbackSeconds = value;
                        saveConfigSafely(config);
                    });
            hindsightLookbackSlider.active = config.autoClipEnabled && config.autoClipHindsightEnabled;
            addLayoutWidget(hindsightLookbackSlider, y);
            y += ROW_SPACING;

            ClickableWidget dimensionToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_dimension", config.autoClipOnDimensionChange, value -> {
                        config.autoClipOnDimensionChange = value;
                        saveConfigSafely(config);
                    });
            dimensionToggle.active = config.autoClipEnabled;
            addLayoutWidget(dimensionToggle, y);

            ClickableWidget bossToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_boss", config.autoClipOnBossKill, value -> {
                        config.autoClipOnBossKill = value;
                        saveConfigSafely(config);
                    });
            bossToggle.active = config.autoClipEnabled;
            addLayoutWidget(bossToggle, y);
            y += ROW_SPACING;

            ClickableWidget killToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_kill", config.autoClipOnKill, value -> {
                        config.autoClipOnKill = value;
                        saveConfigSafely(config);
                    });
            killToggle.active = config.autoClipEnabled;
            addLayoutWidget(killToggle, y);

            ClickableWidget playerKillToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_player_kill", config.autoClipOnPlayerKill, value -> {
                        config.autoClipOnPlayerKill = value;
                        saveConfigSafely(config);
                    });
            playerKillToggle.active = config.autoClipEnabled;
            addLayoutWidget(playerKillToggle, y);
            y += ROW_SPACING;

            // Totem Pop trigger
            ClickableWidget totemPopToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_totem_pop", config.autoClipOnTotemPop, value -> {
                        config.autoClipOnTotemPop = value;
                        saveConfigSafely(config);
                    });
            totemPopToggle.active = config.autoClipEnabled;
            totemPopToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    net.minecraft.text.Text.literal(
                            "Auto-clip when your Totem of Undying saves you from lethal damage.")));
            addLayoutWidget(totemPopToggle, y);

            // Custom Event trigger (experimental)
            ClickableWidget customEventToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_on_custom_event", config.autoClipOnCustomEvent, value -> {
                        config.autoClipOnCustomEvent = value;
                        saveConfigSafely(config);
                        init(); // Rebuild to show/hide custom event list
                    });
            customEventToggle.active = config.autoClipEnabled;
            customEventToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    net.minecraft.text.Text.literal(
                            "(Experimental) Auto-clip on custom events like low health, level up, weather change, etc. Click to enable, then configure events below.")));
            addLayoutWidget(customEventToggle, y);
            y += ROW_SPACING;

            // Custom event trigger checkboxes (shown when custom events enabled)
            if (config.autoClipEnabled && config.autoClipOnCustomEvent) {
                for (int i = 0; i < RecordableConfig.CUSTOM_EVENT_TRIGGERS.length; i++) {
                    final String triggerKey = RecordableConfig.CUSTOM_EVENT_TRIGGERS[i];
                    final String triggerName = RecordableConfig.CUSTOM_EVENT_TRIGGER_NAMES[i];
                    boolean isEnabled = config.customEventTriggers.contains(triggerKey);
                    int xPos = (i % 2 == 0) ? widgetLeft : rightWidgetLeft;
                    ClickableWidget eventToggle = addBooleanToggle(xPos, 0, halfWidgetWidth,
                            triggerName, isEnabled, value -> {
                                if (value) {
                                    if (!config.customEventTriggers.contains(triggerKey)) {
                                        config.customEventTriggers.add(triggerKey);
                                    }
                                } else {
                                    config.customEventTriggers.remove(triggerKey);
                                }
                                saveConfigSafely(config);
                            });
                    addLayoutWidget(eventToggle, y);
                    if (i % 2 == 1 || i == RecordableConfig.CUSTOM_EVENT_TRIGGERS.length - 1) {
                        y += ROW_SPACING;
                    }
                }
            }

            // Kill-montage clip window: seconds captured before and after the finishing blow.
            ClickableWidget killPreSlider = new KillMontageSecondsSlider(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    "Montage Seconds Before", config.autoClipKillPreSeconds, value -> {
                        config.autoClipKillPreSeconds = value;
                        saveConfigSafely(config);
                    });
            killPreSlider.active = config.autoClipEnabled;
            killPreSlider.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal(
                    "Seconds of gameplay captured BEFORE the finishing blow in a kill montage clip. Higher values show more lead-up to the kill.")));
            addLayoutWidget(killPreSlider, y);

            ClickableWidget killPostSlider = new KillMontageSecondsSlider(rightWidgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT,
                    "Montage Seconds After", config.autoClipKillPostSeconds, value -> {
                        config.autoClipKillPostSeconds = value;
                        saveConfigSafely(config);
                    });
            killPostSlider.active = config.autoClipEnabled;
            killPostSlider.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal(
                    "Seconds of gameplay captured AFTER the kill in a kill montage clip. Higher values keep more of the aftermath.")));
            addLayoutWidget(killPostSlider, y);
            y += ROW_SPACING;

            addLayoutWidget(new AutoClipDurationSlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    config.autoClipDuration, value -> {
                        config.autoClipDuration = value;
                        saveConfigSafely(config);
                    }, config), y);
            y += ROW_SPACING;

            // Auto-clip / kill-montage audio toggle.
            ClickableWidget autoClipAudioToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.autoclip_audio", config.autoClipAudio, value -> {
                        config.autoClipAudio = value;
                        saveConfigSafely(config);
                    });
            autoClipAudioToggle.active = config.autoClipEnabled;
            autoClipAudioToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    net.minecraft.text.Text.translatable("screen.recordable.settings.autoclip_audio.tooltip")));
            addLayoutWidget(autoClipAudioToggle, y);
            y += ROW_SPACING;

            // Auto-clip / kill-montage capture FPS (independent of main recording FPS).
            ClickableWidget autoClipFpsSlider = new AutoClipFpsSlider(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT,
                    config.autoClipFps, value -> {
                        config.autoClipFps = value;
                        saveConfigSafely(config);
                    });
            autoClipFpsSlider.active = config.autoClipEnabled;
            addLayoutWidget(autoClipFpsSlider, y);
            y += ROW_SPACING;

            // ============================================================
            // === V1-0.06 Features Section ===
            // ============================================================
            this.v07HeaderY = y;
            y += 12;

            // --- Feature 3: Session Markers & Chapters ---
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.markers_enabled", config.markersEnabled, value -> {
                        config.markersEnabled = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.export_chapters", config.exportChapterFile, value -> {
                        config.exportChapterFile = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.embed_chapters", config.embedChaptersInVideo, value -> {
                        config.embedChaptersInVideo = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.auto_marker_start", config.autoMarkerOnStart, value -> {
                        config.autoMarkerOnStart = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // --- Feature 1: Replay Buffer ---
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.replay_buffer_enabled", config.replayBufferEnabled, value -> {
                        config.replayBufferEnabled = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.replay_notify", config.replayBufferNotify, value -> {
                        config.replayBufferNotify = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;
            
            addLayoutWidget(new ReplayBufferSlider(widgetLeft, 0, halfWidgetWidth, 20,
                    config.replayBufferDurationSeconds, value -> {
                        config.replayBufferDurationSeconds = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addCycleButton(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.replay_quality", RecordableConfig.REPLAY_QUALITIES,
                    () -> config.replayBufferQuality, value -> {
                        config.replayBufferQuality = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // --- Feature 5: Separate Audio Tracks ---
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.separate_audio", config.separateAudioTracks, value -> {
                        config.separateAudioTracks = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.watermarks_enabled", config.watermarksEnabled, value -> {
                        config.watermarksEnabled = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // --- Feature 6: Storage Manager ---
            addLayoutWidget(ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.open_storage"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new StorageManagerScreen(this));
                        }
                    }).dimensions(widgetLeft, 0, halfWidgetWidth, WIDGET_HEIGHT).build(), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.auto_cleanup", config.autoCleanupEnabled, value -> {
                        config.autoCleanupEnabled = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;
            ClickableWidget diskBlockOverrideToggle = addBooleanToggle(widgetLeft, 0, widgetWidth,
                    "screen.recordable.settings.disable_disk_usage_block", config.disableDiskSpaceUsageBlock, value -> {
                        config.disableDiskSpaceUsageBlock = value;
                        saveConfigSafely(config);
                    });
            diskBlockOverrideToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.disable_disk_usage_block.tooltip")));
            addLayoutWidget(diskBlockOverrideToggle, y);
            y += ROW_SPACING;

            // Feature 7: Performance Optimizer moved to the Performance category (V1-0.08).

            // Feature 5: Disk Space Info
            // ============================================================
            // === Chat Notifications Section ===
            // ============================================================
            this.chatNotifyHeaderY = y;
            y += 12;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_recording", config.notifyRecording, value -> {
                        config.notifyRecording = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_clips", config.notifyClips, value -> {
                        config.notifyClips = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_replay", config.notifyReplayBuffer, value -> {
                        config.notifyReplayBuffer = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_autorecord", config.notifyAutoRecord, value -> {
                        config.notifyAutoRecord = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_bookmarks", config.notifyBookmarks, value -> {
                        config.notifyBookmarks = value;
                        saveConfigSafely(config);
                    }), y);
            addLayoutWidget(addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.notify_warnings", config.notifyWarnings, value -> {
                        config.notifyWarnings = value;
                        saveConfigSafely(config);
                    }), y);
            y += ROW_SPACING;

            // ============================================================
            // === Mod Compatibility Section ===
            // ============================================================
            this.compatHeaderY = y;
            y += 12;
            addLayoutWidget(addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.compat_bridge", config.replayCompatBridge, value -> {
                        config.replayCompatBridge = value;
                        saveConfigSafely(config);
                    }), y);
            ClickableWidget compatAutoRecordToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.compat_autorecord", config.replayAutoRecordPlayback, value -> {
                        config.replayAutoRecordPlayback = value;
                        saveConfigSafely(config);
                    });
            compatAutoRecordToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.compat_autorecord.tooltip")));
            addLayoutWidget(compatAutoRecordToggle, y);
            y += ROW_SPACING;
            ClickableWidget compatYieldAudioToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.compat_yield_audio", Boolean.TRUE.equals(config.replayYieldAudioDevice), value -> {
                        config.replayYieldAudioDevice = value;
                        saveConfigSafely(config);
                    });
            compatYieldAudioToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.compat_yield_audio.tooltip")));
            addLayoutWidget(compatYieldAudioToggle, y);
            y += ROW_SPACING;

            // === Deferred Render (Potato Mode) ===
            // Capture at a low FPS during gameplay, then render a smooth video offline.
            this.deferredHeaderY = y;
            y += 12;
            String defKeywords = "deferred render offline smooth potato low fps interpolation";
            ClickableWidget deferredEnabledToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_enabled", config.deferredRenderEnabled, value -> {
                        config.deferredRenderEnabled = value;
                        saveConfigSafely(config);
                    });
            deferredEnabledToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_enabled.tooltip")));
            addLayoutWidget(deferredEnabledToggle, y, defKeywords);
            ClickableWidget deferredAutoRenderToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_auto_render", config.deferredAutoRender, value -> {
                        config.deferredAutoRender = value;
                        saveConfigSafely(config);
                    });
            deferredAutoRenderToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_auto_render.tooltip")));
            addLayoutWidget(deferredAutoRenderToggle, y, defKeywords);
            y += ROW_SPACING;

            String[] captureFpsLabels = new String[RecordableConfig.DEFERRED_CAPTURE_FPS.length];
            for (int i = 0; i < captureFpsLabels.length; i++) {
                captureFpsLabels[i] = String.valueOf(RecordableConfig.DEFERRED_CAPTURE_FPS[i]);
            }
            ClickableWidget captureFpsButton = addCycleButton(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_capture_fps", captureFpsLabels,
                    () -> String.valueOf(config.deferredCaptureFps),
                    value -> {
                        try {
                            config.deferredCaptureFps = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                        saveConfigSafely(config);
                    });
            captureFpsButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_capture_fps.tooltip")));
            addLayoutWidget(captureFpsButton, y, defKeywords);
            String[] targetFpsLabels = new String[RecordableConfig.DEFERRED_TARGET_FPS.length];
            for (int i = 0; i < targetFpsLabels.length; i++) {
                targetFpsLabels[i] = String.valueOf(RecordableConfig.DEFERRED_TARGET_FPS[i]);
            }
            ClickableWidget targetFpsButton = addCycleButton(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_target_fps", targetFpsLabels,
                    () -> String.valueOf(config.deferredTargetFps),
                    value -> {
                        try {
                            config.deferredTargetFps = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                        saveConfigSafely(config);
                    });
            targetFpsButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_target_fps.tooltip")));
            addLayoutWidget(targetFpsButton, y, defKeywords);
            y += ROW_SPACING;

            ClickableWidget interpButton = addCycleButton(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_interpolation", RecordableConfig.DEFERRED_INTERPOLATION,
                    () -> config.deferredInterpolation,
                    value -> {
                        config.deferredInterpolation = value;
                        saveConfigSafely(config);
                    });
            interpButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_interpolation.tooltip")));
            addLayoutWidget(interpButton, y, defKeywords);
            ClickableWidget deferredKeepFramesToggle = addBooleanToggle(rightWidgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_keep_frames", config.deferredKeepTempFrames, value -> {
                        config.deferredKeepTempFrames = value;
                        saveConfigSafely(config);
                    });
            deferredKeepFramesToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_keep_frames.tooltip")));
            addLayoutWidget(deferredKeepFramesToggle, y, defKeywords);
            y += ROW_SPACING;

            ClickableWidget deferredShowPromptToggle = addBooleanToggle(widgetLeft, 0, halfWidgetWidth,
                    "screen.recordable.settings.deferred_show_prompt", config.deferredShowRenderPrompt, value -> {
                        config.deferredShowRenderPrompt = value;
                        saveConfigSafely(config);
                    });
            deferredShowPromptToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_show_prompt.tooltip")));
            addLayoutWidget(deferredShowPromptToggle, y, defKeywords);
            y += ROW_SPACING;

            ClickableWidget pendingRendersButton = ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.deferred_pending"),
                    button -> {
                        if (this.client != null) {
                            this.client.setScreen(new dev.recordable.PendingRendersScreen(this));
                        }
                    })
                    .dimensions(widgetLeft, 0, widgetWidth, WIDGET_HEIGHT)
                    .build();
            pendingRendersButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                    Text.translatable("screen.recordable.settings.deferred_pending.tooltip")));
            addLayoutWidget(pendingRendersButton, y, defKeywords);
            y += ROW_SPACING;

            this.diskSpaceInfoY = y;
            y += 14;

            this.ffmpegStatusY = y;
            y += 18;

            this.contentHeight = y;
            this.fullContentHeight = y;

            int halfButtonWidth = Math.max(84, (widgetWidth - 6) / 2);
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.open_folder"),
                    button -> openRecordingsFolder()
            ).dimensions(widgetLeft, this.footerY, halfButtonWidth, WIDGET_HEIGHT).build());
            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("screen.recordable.settings.done"),
                    button -> saveAndClose()
            ).dimensions(widgetLeft + halfButtonWidth + 6, this.footerY, halfButtonWidth, WIDGET_HEIGHT).build());

            updateWidgetLayout();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.error("Failed to initialize Record-able settings screen.", throwable);
            this.statusMessage = Text.literal("Failed to open settings. Check logs for details.");
            this.statusIsError = true;
            addFallbackCloseButton();
        }
    }

    private void addLayoutWidget(ClickableWidget widget, int baseY) {
        addLayoutWidget(widget, baseY, null);
    }

    private void addLayoutWidget(ClickableWidget widget, int baseY, String keywords) {
        this.layoutWidgets.add(new LayoutWidget(widget, baseY, keywords));
        this.addDrawableChild(widget);
    }

    private void addFallbackCloseButton() {
        int buttonWidth = 150;
        int x = (this.width - buttonWidth) / 2;
        int y = Math.max(24, this.height - 34);
        this.panelWidth = Math.max(220, buttonWidth + 40);
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, y - 48);
        this.panelBottom = Math.min(this.height - 6, y + WIDGET_HEIGHT + 12);
        this.panelBodyTop = this.panelTop + 24;
        this.panelBodyBottom = y - 6;
        this.footerY = y;
        this.contentHeight = 0;
        this.videoHeaderY = this.panelTop + 24;
        this.audioHeaderY = this.videoHeaderY;
        this.generalHeaderY = this.videoHeaderY;
        this.androidHeaderY = this.videoHeaderY;
        this.autoRecordHeaderY = this.videoHeaderY;
        this.appearanceHeaderY = this.videoHeaderY;
        this.positionsHeaderY = this.videoHeaderY;
        this.performanceHeaderY = this.videoHeaderY;
        this.advancedHeaderY = this.videoHeaderY;
        this.autoClipHeaderY = this.videoHeaderY;
        this.v07HeaderY = this.videoHeaderY;
        this.chatNotifyHeaderY = this.videoHeaderY;
        this.compatHeaderY = this.videoHeaderY;
        this.deferredHeaderY = this.videoHeaderY;
        this.bitrateLabelY = this.videoHeaderY + 12;
        this.performanceHintY = this.bitrateLabelY + 12;
        this.outputLabelY = this.performanceHintY + 12;
        this.outputPathY = this.outputLabelY + 12;
        this.diskSpaceInfoY = this.outputPathY + 12;
        this.ffmpegStatusY = this.diskSpaceInfoY + 12;
        this.windowsAudioWarningY = this.ffmpegStatusY + 12;
        this.windowsAudioWarningHeight = 0;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.recordable.settings.done"), button -> saveAndClose())
                .dimensions(x, y, buttonWidth, WIDGET_HEIGHT)
                .build());
    }

    private ClickableWidget addCycleButton(int x, int y, int width, String translationKey, String[] values,
                                           Supplier<String> getter, Consumer<String> setter) {
        return CycleButton.create(x, y, width, WIDGET_HEIGHT,
                cycleMessage(translationKey, getter.get()),
                button -> {
                    String next = nextValue(values, getter.get());
                    try {
                        setter.accept(next);
                        button.setMessage(cycleMessage(translationKey, next));
                    } catch (Throwable throwable) {
                        RecordableMod.LOGGER.warn("Failed to update setting {} to {}.", translationKey, next, throwable);
                    }
                },
                button -> {
                    String prev = prevValue(values, getter.get());
                    try {
                        setter.accept(prev);
                        button.setMessage(cycleMessage(translationKey, prev));
                    } catch (Throwable throwable) {
                        RecordableMod.LOGGER.warn("Failed to update setting {} to {}.", translationKey, prev, throwable);
                    }
                });
    }

    private ClickableWidget addBooleanToggle(int x, int y, int width, String translationKey, boolean value, Consumer<Boolean> setter) {
        return CyclingButtonWidget.onOffBuilder(value).build(
                x,
                y,
                width,
                WIDGET_HEIGHT,
                Text.translatable(translationKey),
                (button, newValue) -> {
                    try {
                        setter.accept(newValue);
                    } catch (Throwable throwable) {
                        RecordableMod.LOGGER.warn("Failed to update setting {} to {}.", translationKey, newValue, throwable);
                    }
                }
        );
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX < this.panelLeft || mouseX > this.panelLeft + this.panelWidth || mouseY < this.panelBodyTop || mouseY > this.panelBodyBottom) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int maxScroll = Math.max(0, this.contentHeight - (this.panelBodyBottom - this.panelBodyTop));
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int delta = (int) Math.round(verticalAmount * -20.0D);
        if (delta == 0) {
            delta = verticalAmount > 0 ? -20 : 20;
        }
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        updateWidgetLayout();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingScrollbar && button == 0) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) return false;
        int barLeft = this.panelLeft + this.panelWidth - 4;
        return mouseX >= barLeft - 2 && mouseX <= barLeft + 5
                && mouseY >= this.panelBodyTop && mouseY <= this.panelBodyBottom;
    }

    private void scrollToMouse(double mouseY) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) return;
        int thumbHeight = Math.max(22, (int) (viewportHeight * (viewportHeight / (double) this.contentHeight)));
        int available = viewportHeight - thumbHeight;
        if (available <= 0) {
            this.scrollOffset = 0;
        } else {
            double rel = (mouseY - this.panelBodyTop - thumbHeight / 2.0) / available;
            rel = Math.max(0.0, Math.min(1.0, rel));
            this.scrollOffset = (int) Math.round(rel * maxScroll);
        }
        updateWidgetLayout();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Let the search box handle keys (including arrows) when it is focused.
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == 264) {
            this.scrollOffset += ROW_SPACING;
            updateWidgetLayout();
            return true;
        }
        if (keyCode == 265) {
            this.scrollOffset -= ROW_SPACING;
            updateWidgetLayout();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void updateWidgetLayout() {
        boolean searching = !this.searchQuery.isEmpty();
        if (searching) {
            updateWidgetLayoutSearching();
            return;
        }

        this.searchMatchRows = -1;
        this.contentHeight = this.fullContentHeight;
        int maxScroll = Math.max(0, this.contentHeight - (this.panelBodyBottom - this.panelBodyTop));
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));

        for (LayoutWidget item : this.layoutWidgets) {
            int y = this.panelBodyTop + item.baseY - this.scrollOffset;
            ClickableWidget widget = item.widget;
            if (widget instanceof ColorPickerWidget colorPickerWidget) {
                colorPickerWidget.setPosition(widget.getX(), y);
            } else {
                widget.setY(y);
            }

            boolean visible = y >= this.panelBodyTop && y + widget.getHeight() <= this.panelBodyBottom;
            widget.visible = visible;
            widget.active = visible;
        }
    }

    /**
     * Layout used while a search query is active: matching widgets are re-flowed into a
     * compact, gap-free list (preserving their left/right column pairing via shared baseY)
     * and all non-matching widgets are hidden so nothing overlaps.
     */
    private void updateWidgetLayoutSearching() {
        java.util.List<Integer> distinctBaseYs = new java.util.ArrayList<>();
        for (LayoutWidget item : this.layoutWidgets) {
            if (matchesSearch(item) && !distinctBaseYs.contains(item.baseY)) {
                distinctBaseYs.add(item.baseY);
            }
        }
        java.util.Collections.sort(distinctBaseYs);
        this.searchMatchRows = distinctBaseYs.size();

        this.contentHeight = Math.max(0, distinctBaseYs.size() * ROW_SPACING);
        int maxScroll = Math.max(0, this.contentHeight - (this.panelBodyBottom - this.panelBodyTop));
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));

        for (LayoutWidget item : this.layoutWidgets) {
            ClickableWidget widget = item.widget;
            if (matchesSearch(item)) {
                int rowIndex = distinctBaseYs.indexOf(item.baseY);
                int y = this.panelBodyTop + rowIndex * ROW_SPACING - this.scrollOffset;
                if (widget instanceof ColorPickerWidget colorPickerWidget) {
                    colorPickerWidget.setPosition(widget.getX(), y);
                } else {
                    widget.setY(y);
                }
                boolean visible = y >= this.panelBodyTop && y + widget.getHeight() <= this.panelBodyBottom;
                widget.visible = visible;
                widget.active = visible;
            } else {
                widget.visible = false;
                widget.active = false;
            }
        }
    }

    /** True when the widget's label or keywords contain the current (lowercased) search query. */
    private boolean matchesSearch(LayoutWidget item) {
        if (this.searchQuery.isEmpty()) {
            return true;
        }
        StringBuilder sb = new StringBuilder();
        try {
            Text message = item.widget.getMessage();
            if (message != null) {
                sb.append(message.getString().toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        if (item.keywords != null) {
            sb.append(' ').append(item.keywords);
        }
        return sb.toString().contains(this.searchQuery);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();
        int accent = colors.accent;
        int left = this.panelLeft - 6;
        int right = this.panelLeft + this.panelWidth + 6;

        // Draw themed panel
        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(context, left, this.panelTop - 6, right, this.panelBottom);
        } else {
            ThemedPanel.drawPanel(context, left, this.panelTop - 6, right, this.panelBottom);
        }

        // Title with theme-appropriate decoration
        if (preset == ThemePreset.VHS || preset == ThemePreset.NEON) {
            TypewriterText.renderFlickerText(context, this.textRenderer,
                    "[ " + this.title.getString() + " ]",
                    this.width / 2 - this.textRenderer.getWidth("[ " + this.title.getString() + " ]") / 2,
                    this.panelTop, colors.headerText);
        } else if (preset == ThemePreset.CINEMA) {
            dev.recordable.compat.RenderHelper.drawCenteredText(context, this.textRenderer,
                    "★ " + this.title.getString() + " ★",
                    this.width / 2, this.panelTop, colors.headerText);
        } else {
            dev.recordable.compat.RenderHelper.drawCenteredText(context, this.textRenderer, this.title, this.width / 2, this.panelTop, colors.headerText);
        }

        super.render(context, mouseX, mouseY, delta);

        // Section headers and static labels are tied to fixed scroll positions, so they only
        // make sense when not searching (search re-flows the widgets into a compact list).
        if (this.searchQuery.isEmpty()) {
            drawHeader(context, "screen.recordable.settings.video", this.videoHeaderY);
            drawHeader(context, "screen.recordable.settings.audio", this.audioHeaderY);
            drawHeader(context, "screen.recordable.settings.general", this.generalHeaderY);
            if (PlatformUtils.isAndroid()) {
                drawHeader(context, "screen.recordable.settings.android", this.androidHeaderY);
            }
            drawHeader(context, "screen.recordable.settings.auto_record.section", this.autoRecordHeaderY);
            drawHeader(context, "screen.recordable.settings.appearance", this.appearanceHeaderY);
            drawHeader(context, "screen.recordable.settings.positions", this.positionsHeaderY);
            drawHeader(context, "screen.recordable.settings.performance_section", this.performanceHeaderY);
            drawHeader(context, "screen.recordable.settings.advanced", this.advancedHeaderY);
            drawHeader(context, "screen.recordable.settings.autoclip.section", this.autoClipHeaderY);
            drawHeader(context, "screen.recordable.settings.v07_section", this.v07HeaderY);
            drawHeader(context, "screen.recordable.settings.notify_section", this.chatNotifyHeaderY);
            drawHeader(context, "screen.recordable.settings.compat_section", this.compatHeaderY);
            drawHeader(context, "screen.recordable.settings.deferred_section", this.deferredHeaderY);

            drawLabel(context, Text.literal("Rename File Name"), this.filenamePatternLabelY, colors.textSecondary);
            drawLabel(context, Text.translatable("screen.recordable.settings.bitrate_hint"), this.bitrateLabelY, colors.textMuted);
            String perfHint = RecordableConfig.get().getPerformanceHint();
            int perfHintColor = perfHint.contains("drop") || perfHint.contains("expensive") ? 0xFFFFCC66 : colors.textMuted;
            drawWrappedLabel(context, Text.literal("Performance: " + perfHint), this.performanceHintY, this.panelWidth - 28, perfHintColor);
            drawLabel(context, Text.translatable("screen.recordable.settings.output_dir"), this.outputLabelY, colors.textMuted);
            drawWrappedLabel(context, Text.literal(getDisplayOutputPath()), this.outputPathY, this.panelWidth - 28, colors.textMuted);

            if (this.windowsAudioWarningHeight > 0) {
                Text platformWarning = getPlatformAudioWarningText();
                if (platformWarning != null) {
                    int warningColor = 0xFFFFCC66;
                    drawWrappedLabel(context, platformWarning, this.windowsAudioWarningY,
                            this.panelWidth - 28, warningColor);
                }
            }

            // Feature 5: Disk Space Info
            try {
                String diskInfo = "Disk: " + DiskSpaceGuardian.getFormattedFreeSpace(RecordableConfig.get().getOutputDirectory()) + " free";
                drawLabel(context, Text.literal(diskInfo), this.diskSpaceInfoY, colors.textMuted);
            } catch (Exception ignored) {}

            if (this.ffmpegStatus != null) {
                drawWrappedLabel(context, this.ffmpegStatus, this.ffmpegStatusY,
                        this.panelWidth - 28,
                        this.ffmpegStatusIsError ? colors.textError : colors.textMuted);
            }
        } else if (this.searchMatchRows == 0) {
            dev.recordable.compat.RenderHelper.drawCenteredText(context, this.textRenderer,
                    Text.translatable("screen.recordable.settings.no_search_results"),
                    this.width / 2,
                    this.panelBodyTop + 12,
                    colors.textMuted);
        }

        if (this.statusMessage != null) {
            dev.recordable.compat.RenderHelper.drawCenteredText(
                    context,
                    this.textRenderer,
                    this.statusMessage,
                    this.width / 2,
                    this.panelBottom - 12,
                    this.statusIsError ? colors.textError : colors.textMuted
            );
        }

        // VHS status indicator in corner
        if (preset == ThemePreset.VHS) {
            boolean recording = RecordingManager.getInstance().isRecording();
            if (recording) {
                ThemedPanel.drawVhsStatusBadge(context, this.textRenderer, "● REC", right - 50, this.panelTop - 3, true);
            }
        }

        renderScrollbar(context, accent);
    }

    private void drawHeader(DrawContext context, String key, int baseY) {
        int y = this.panelBodyTop + baseY - this.scrollOffset;
        if (y < this.panelBodyTop - 12 || y > this.panelBodyBottom + 2) {
            return;
        }
        String text = Text.translatable(key).getString();
        ThemedPanel.drawSectionHeader(context, this.textRenderer, text, this.panelLeft + 14, y, this.panelWidth - 28);
    }

    private void drawLabel(DrawContext context, Text text, int baseY, int color) {
        drawLabel(context, text, baseY, color, this.panelLeft + 14);
    }

    private void drawLabel(DrawContext context, Text text, int baseY, int color, int x) {
        int y = this.panelBodyTop + baseY - this.scrollOffset;
        if (y < this.panelBodyTop - 12 || y > this.panelBodyBottom + 2) {
            return;
        }
        int maxWidth = Math.max(8, this.panelLeft + this.panelWidth - 14 - x);
        RenderHelper.drawText(context, this.textRenderer, Text.literal(clampPanelText(text.getString(), maxWidth)), x, y, color);
    }

    private void drawWrappedLabel(DrawContext context, Text text, int baseY, int wrapWidth, int color) {
        int y = this.panelBodyTop + baseY - this.scrollOffset;
        if (y < this.panelBodyTop - 18 || y > this.panelBodyBottom + 2) {
            return;
        }
        int safeWrapWidth = Math.max(32, wrapWidth);
        int lineCount = Math.max(1, this.textRenderer.wrapLines(text, safeWrapWidth).size());
        if (y + lineCount * 11 > this.panelBodyBottom + 1) {
            return;
        }
        dev.recordable.compat.RenderHelper.drawWrappedText(context, this.textRenderer, text, this.panelLeft + 14, y, safeWrapWidth, color);
    }

    private String clampPanelText(String text, int maxWidth) {
        if (text == null || maxWidth <= 8) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        int trimmedWidth = Math.max(1, maxWidth - this.textRenderer.getWidth("..."));
        return this.textRenderer.trimToWidth(text, trimmedWidth) + "...";
    }

    private void renderScrollbar(DrawContext context, int accent) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            return;
        }

        int barLeft = this.panelLeft + this.panelWidth - 4;
        int thumbHeight = Math.max(22, (int) (viewportHeight * (viewportHeight / (double) this.contentHeight)));
        int available = viewportHeight - thumbHeight;
        int thumbTop = this.panelBodyTop + (int) ((this.scrollOffset / (double) maxScroll) * available);
        ThemedPanel.drawScrollbar(context, barLeft, this.panelBodyTop, this.panelBodyBottom, thumbTop, thumbHeight);
    }

    @Override
    public void close() {
        saveAndClose();
    }

    private static Text cycleEncoderMessage(RecordableConfig.VideoEncoder encoder) {
        RecordableConfig.VideoEncoder value = encoder == null
                ? RecordableConfig.VideoEncoder.SOFTWARE
                : encoder;
        return Text.literal("Video Encoder: " + encoderRuntimeLabel(value));
    }

    /**
     * Label for a video encoder. For SOFTWARE we append the concrete codec that will
     * actually run (e.g. "Software (x264) [mpeg4]") so the menu reflects the real
     * runtime encoder instead of a stale/assumed one - important on Android where the
     * FFmpeg build may not contain libx264.
     */
    private static String encoderRuntimeLabel(RecordableConfig.VideoEncoder value) {
        String label = value.displayName;
        if (value == RecordableConfig.VideoEncoder.SOFTWARE) {
            String codec = dev.recordable.FFmpegEncoder.getCachedSoftwareCodec();
            if (codec != null) {
                label = label + " [" + codec + "]";
            }
        }
        return label;
    }

    private static RecordableConfig.VideoEncoder nextEncoder(List<RecordableConfig.VideoEncoder> values,
                                                             RecordableConfig.VideoEncoder current) {
        if (values == null || values.isEmpty()) {
            return RecordableConfig.VideoEncoder.SOFTWARE;
        }

        int index = values.indexOf(current);
        if (index < 0) {
            return values.get(0);
        }
        return values.get((index + 1) % values.size());
    }

    private static RecordableConfig.VideoEncoder prevEncoder(List<RecordableConfig.VideoEncoder> values,
                                                             RecordableConfig.VideoEncoder current) {
        if (values == null || values.isEmpty()) {
            return RecordableConfig.VideoEncoder.SOFTWARE;
        }

        int index = values.indexOf(current);
        if (index < 0) {
            return values.get(0);
        }
        return values.get((index - 1 + values.size()) % values.size());
    }

    private static Text cycleMessage(String translationKey, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (translationKey.startsWith("screen.recordable.settings.auto_record.")) {
            Text translatedValue = Text.translatable(translationKey + ".value." + normalized);
            return Text.translatable(translationKey, translatedValue);
        }
        if (translationKey.equals("screen.recordable.settings.replay_quality")) {
            return Text.translatable(translationKey, Text.literal(replayQualityLabel(normalized)));
        }
        return Text.translatable(translationKey, Text.literal(displayValue(value)));
    }

    /** Friendly, self-describing labels for the replay-buffer quality presets. */
    private static String replayQualityLabel(String value) {
        switch (value == null ? "" : value) {
            case "source":      return "Source";
            case "balanced":    return "720p30";
            case "performance": return "480p30";
            case "high":        return "1080p60";
            default:            return displayValue(value);
        }
    }

    private static String displayValue(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        if (value.length() <= 4) {
            return value.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String nextValue(String[] values, String current) {
        if (values == null || values.length == 0) {
            return current;
        }
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(current == null ? "" : current)) {
                return values[(index + 1) % values.length];
            }
        }
        return values[0];
    }

    private static String prevValue(String[] values, String current) {
        if (values == null || values.length == 0) {
            return current;
        }
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(current == null ? "" : current)) {
                return values[(index - 1 + values.length) % values.length];
            }
        }
        return values[0];
    }

    private static Text audioDelayPresetMessage(RecordableConfig.AudioDelayPreset preset, int effectiveMs) {
        if (preset == null) preset = RecordableConfig.AudioDelayPreset.AUTO;
        return Text.literal("Audio Delay: " + preset.displayName + " (" + effectiveMs + "ms)");
    }

    /**
     * Runs a short microphone test on a background thread (so the UI does not freeze), then
     * reports the measured level back to the player via chat and on the button itself. This is
     * the "Test Mic" feature: it uses the exact capture path the recorder uses, so it reliably
     * tells the user whether their selected mic actually produces sound.
     */
    private void runMicTest(ButtonWidget button, String ffExe) {
        final RecordableConfig cfg = RecordableConfig.get();
        final String device = cfg.microphoneDevice;
        button.active = false;
        button.setMessage(Text.translatable("screen.recordable.settings.test_mic_running"));
        Thread t = new Thread(() -> {
            AudioCapture.MicTestResult result;
            try {
                result = AudioCapture.testMicrophoneLevel(ffExe, device, 3);
            } catch (Throwable th) {
                result = new AudioCapture.MicTestResult(false,
                        device == null ? "auto" : device, Double.NaN, Double.NaN,
                        "Mic test crashed: " + th.getMessage());
            }
            final AudioCapture.MicTestResult r = result;
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            mc.execute(() -> {
                button.active = true;
                button.setMessage(Text.translatable("screen.recordable.settings.test_mic"));
                String prefix = r.hasSignal() ? "✔ Mic test: " : "⚠ Mic test: ";
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(prefix + r.message()), false);
                    if (r.deviceName() != null && !r.deviceName().isBlank()) {
                        mc.player.sendMessage(Text.literal("   Device: " + r.deviceName()), false);
                    }
                } else {
                    button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                            Text.literal(r.message())));
                }
                RecordableMod.LOGGER.info("Mic test result: success={} signal={} device='{}' mean={} max={} msg='{}'",
                        r.success(), r.hasSignal(), r.deviceName(), r.meanDb(), r.maxDb(), r.message());
            });
        }, "recordable-mic-test");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Builds the ordered list of microphone options shown by the device-cycle button:
     * always starts with "auto", followed by detected input devices, and includes the
     * currently-configured device even if it isn't in the detected list (e.g. unplugged
     * or set on another machine) so the user's selection is never silently lost.
     */
    private static java.util.List<String> buildMicDeviceOptions(String ffExe, String configured) {
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("auto");
        try {
            for (String d : AudioCapture.listMicrophoneDevices(ffExe)) {
                if (d != null && !d.isBlank() && !opts.contains(d)) {
                    opts.add(d);
                }
            }
        } catch (Throwable ignored) {
        }
        String cur = (configured == null || configured.isBlank()) ? "auto" : configured.trim();
        if (!opts.contains(cur)) {
            opts.add(cur);
        }
        return opts;
    }

    /** Label for the microphone device cycle button. */
    private static Text micDeviceButtonLabel(String device) {
        String name = (device == null || device.isBlank() || device.equalsIgnoreCase("auto"))
                ? "Auto (default mic)" : device;
        String shown = name.length() > 26 ? name.substring(0, 25) + "\u2026" : name;
        return Text.literal("Mic: " + shown);
    }

    /** Tooltip for the microphone device cycle button, listing all options and marking the current one. */
    private static Text micDeviceTooltip(java.util.List<String> opts, String current) {
        String cur = (current == null || current.isBlank()) ? "auto" : current.trim();
        StringBuilder sb = new StringBuilder("Click to cycle the microphone input device.\n\nAvailable inputs:");
        for (String o : opts) {
            String label = o.equalsIgnoreCase("auto") ? "auto (system default microphone)" : o;
            sb.append("\n").append(o.equals(cur) ? "\u2714 " : "   ").append(label);
        }
        if (opts.size() <= 1) {
            sb.append("\n\nNo microphones detected. Plug in/enable a mic, then click to re-scan.");
        } else {
            sb.append("\n\nThe list is re-scanned on each click, so newly plugged mics appear.");
        }
        return Text.literal(sb.toString());
    }

    private static Text audioStatusText(boolean detected) {
        if (detected) {
            String method = PlatformUtils.getAudioMethodDescription();
            return Text.literal("Audio: " + method + " Detected ✓")
                    .styled(style -> style.withColor(0x66FF66));
        }
        return Text.literal("Audio: Not Available - Video Only")
                .styled(style -> style.withColor(0xFFCC66));
    }

    /**
     * Returns the appropriate audio warning/info text for the current platform,
     * or {@code null} if no platform-specific message is needed.
     */
    private static Text getPlatformAudioWarningText() {
        if (PlatformUtils.isAndroid()) {
            return ANDROID_AUDIO_INFO_TEXT;
        }
        if (PlatformUtils.isWindows()) {
            return WINDOWS_AUDIO_WARNING_TEXT;
        }
        if (PlatformUtils.isLinux()) {
            return LINUX_AUDIO_INFO_TEXT;
        }
        if (PlatformUtils.isMacOS()) {
            return MACOS_AUDIO_INFO_TEXT;
        }
        return null;
    }

    private static void saveConfigSafely(RecordableConfig config) {
        if (config == null) {
            return;
        }
        try {
            config.save();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to save Record-able config from settings screen.", throwable);
        }
    }

    private void saveAndClose() {
        saveConfigSafely(RecordableConfig.get());
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private String getDisplayOutputPath() {
        try {
            return RecordingManager.getInstance().getCurrentOutputDirectory().toString();
        } catch (Throwable throwable) {
            return "(output path unavailable)";
        }
    }

    /**
     * Opens the native OS folder picker and, if the user chooses a folder, writes
     * it into the recordings-path field (which persists it). The picker runs off
     * the render thread, so the result is applied back on the client thread.
     */
    private void chooseOutputFolder(TextFieldWidget field) {
        String current = field.getText();
        String defaultDir;
        try {
            defaultDir = RecordableConfig.get().getOutputDirectory().toString();
        } catch (Throwable t) {
            defaultDir = current;
        }
        NativeFolderPicker.pickFolder("Choose recordings folder", defaultDir, chosen -> {
            if (chosen == null || chosen.isBlank()) return;
            if (this.client != null) {
                this.client.execute(() -> field.setText(chosen.trim()));
            } else {
                field.setText(chosen.trim());
            }
        });
    }

    private void openRecordingsFolder() {
        Path folder;
        try {
            RecordableConfig.get().save();
            folder = RecordingManager.getInstance().getCurrentOutputDirectory();
            Files.createDirectories(folder);
            Util.getOperatingSystem().open(folder.toUri());
            this.statusMessage = Text.translatable("screen.recordable.settings.opened_folder");
            this.statusIsError = false;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to open Record-able recordings folder.", throwable);
            this.statusMessage = Text.translatable("screen.recordable.settings.open_folder_failed");
            this.statusIsError = true;
        }
    }

    private static final class LayoutWidget {
        final ClickableWidget widget;
        final int baseY;
        final String keywords;

        LayoutWidget(ClickableWidget widget, int baseY, String keywords) {
            this.widget = widget;
            this.baseY = baseY;
            this.keywords = keywords;
        }
    }

    private static final class FpsSlider extends SliderWidget {
        private final IntConsumer setter;

        private FpsSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalizeFps(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.translatable("screen.recordable.settings.fps", getCurrentFps()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentFps());
            updateMessage();
        }

        private int getCurrentFps() {
            int index = (int) Math.round(this.value * (RecordableConfig.FPS_VALUES.length - 1));
            index = Math.max(0, Math.min(RecordableConfig.FPS_VALUES.length - 1, index));
            return RecordableConfig.FPS_VALUES[index];
        }

        private static double normalizeFps(int fps) {
            for (int index = 0; index < RecordableConfig.FPS_VALUES.length; index++) {
                if (RecordableConfig.FPS_VALUES[index] == fps) {
                    return index / (double) (RecordableConfig.FPS_VALUES.length - 1);
                }
            }
            return 0.5D;
        }
    }

    private static final class MaxFileSizeSlider extends SliderWidget {
        private static final int MAX_MB = 10_240;
        private final IntConsumer setter;

        private MaxFileSizeSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int value = getCurrentValue();
            this.setMessage(value <= 0
                    ? Text.translatable("screen.recordable.settings.max_file_size.unlimited")
                    : Text.translatable("screen.recordable.settings.max_file_size", value));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentValue());
            updateMessage();
        }

        private int getCurrentValue() {
            int rounded = (int) Math.round(this.value * (MAX_MB / 100.0D)) * 100;
            return Math.max(0, Math.min(MAX_MB, rounded));
        }

        private static double normalize(int value) {
            int clamped = Math.max(0, Math.min(MAX_MB, value));
            return clamped / (double) MAX_MB;
        }
    }

    private static final class ReplayBufferSlider extends SliderWidget {
        private static final int MIN_SECONDS = 10;
        private static final int MAX_SECONDS = 300;
        private final IntConsumer setter;

        private ReplayBufferSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int seconds = getCurrentSeconds();
            String label = seconds >= 60
                    ? String.format("Replay Buffer: %dm %ds", seconds / 60, seconds % 60)
                    : "Replay Buffer: " + seconds + "s";
            this.setMessage(Text.literal(label));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentSeconds());
            updateMessage();
        }

        private int getCurrentSeconds() {
            int raw = (int) Math.round(this.value * (MAX_SECONDS - MIN_SECONDS)) + MIN_SECONDS;
            int snapped = Math.round(raw / 5.0F) * 5;
            return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
            return (clamped - MIN_SECONDS) / (double) (MAX_SECONDS - MIN_SECONDS);
        }
    }

    /**
     * Slider for custom audio delay (-500 to 500ms).
     * Positive values delay the audio (audio is early); negative values advance the audio (audio is late).
     */
    private static final class AudioDelaySlider extends SliderWidget {
        private static final int MIN_MS = -500;
        private static final int MAX_MS = 500;
        private final IntConsumer setter;

        private AudioDelaySlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int ms = getCurrentMs();
            String sign = ms > 0 ? "+" : "";
            this.setMessage(Text.literal("Custom Delay: " + sign + ms + " ms"));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentMs());
            updateMessage();
        }

        private int getCurrentMs() {
            int raw = (int) Math.round(this.value * (MAX_MS - MIN_MS)) + MIN_MS;
            int snapped = Math.round(raw / 5.0F) * 5;
            return Math.max(MIN_MS, Math.min(MAX_MS, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_MS, Math.min(MAX_MS, value));
            return (clamped - MIN_MS) / (double) (MAX_MS - MIN_MS);
        }
    }

    private static final class AudioVolumeSlider extends SliderWidget {
        private static final int MAX_VOLUME = 200;
        private final IntConsumer setter;

        private AudioVolumeSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int vol = getCurrentVolume();
            String label = vol == 0 ? "Muted" : vol + "%";
            this.setMessage(Text.literal("Audio Volume: " + label));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentVolume());
            updateMessage();
        }

        private int getCurrentVolume() {
            int raw = (int) Math.round(this.value * MAX_VOLUME);
            int snapped = Math.round(raw / 5.0F) * 5;
            return Math.max(0, Math.min(MAX_VOLUME, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(0, Math.min(MAX_VOLUME, value));
            return clamped / (double) MAX_VOLUME;
        }
    }

    /** Slider for a mixing level (0%-200%, snapped to 5% steps) with a custom label. */
    private static final class MixVolumeSlider extends SliderWidget {
        private static final int MAX_VOLUME = 200;
        private final String label;
        private final IntConsumer setter;

        private MixVolumeSlider(int x, int y, int width, int height, String label, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.label = label;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int vol = getCurrentVolume();
            this.setMessage(Text.literal(label + ": " + (vol == 0 ? "Muted" : vol + "%")));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentVolume());
            updateMessage();
        }

        private int getCurrentVolume() {
            int raw = (int) Math.round(this.value * MAX_VOLUME);
            int snapped = Math.round(raw / 5.0F) * 5;
            return Math.max(0, Math.min(MAX_VOLUME, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(0, Math.min(MAX_VOLUME, value));
            return clamped / (double) MAX_VOLUME;
        }
    }

    /** Slider for overlay scale (50%-200%, snapped to 10% steps). */
    private static final class OverlayScaleSlider extends SliderWidget {
        private static final int MIN_SCALE = 50;
        private static final int MAX_SCALE = 200;
        private final IntConsumer setter;

        private OverlayScaleSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = getCurrentScale();
            String label;
            if (pct == 100) {
                label = "100% (Default)";
            } else if (pct < 100) {
                label = pct + "% (Smaller)";
            } else {
                label = pct + "% (Larger)";
            }
            this.setMessage(Text.literal("Overlay Scale: " + label));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentScale());
            updateMessage();
        }

        private int getCurrentScale() {
            int raw = (int) Math.round(this.value * (MAX_SCALE - MIN_SCALE)) + MIN_SCALE;
            // Snap to nearest 10%
            int snapped = Math.round(raw / 10.0F) * 10;
            return Math.max(MIN_SCALE, Math.min(MAX_SCALE, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
            return (clamped - MIN_SCALE) / (double) (MAX_SCALE - MIN_SCALE);
        }
    }

    /** Slider for mouse pointer scale (50%-300%, snapped to 10% steps). */
    private static final class MousePointerScaleSlider extends SliderWidget {
        private static final int MIN_SCALE = 50;
        private static final int MAX_SCALE = 300;
        private final IntConsumer setter;

        private MousePointerScaleSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = getCurrentScale();
            this.setMessage(Text.translatable("screen.recordable.settings.pointer_size", pct + "%"));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentScale());
            updateMessage();
        }

        private int getCurrentScale() {
            int raw = (int) Math.round(this.value * (MAX_SCALE - MIN_SCALE)) + MIN_SCALE;
            int snapped = Math.round(raw / 10.0F) * 10;
            return Math.max(MIN_SCALE, Math.min(MAX_SCALE, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
            return (clamped - MIN_SCALE) / (double) (MAX_SCALE - MIN_SCALE);
        }
    }

    /**
     * Slider for the kill-montage pre-roll / post-roll window (seconds before / after the
     * finishing blow). Snaps to whole seconds in the range {@value #MIN_SECONDS}-{@value #MAX_SECONDS}.
     */
    private static final class KillMontageSecondsSlider extends SliderWidget {
        private static final int MIN_SECONDS = 0;
        private static final int MAX_SECONDS = 10;
        private final String label;
        private final IntConsumer setter;

        private KillMontageSecondsSlider(int x, int y, int width, int height, String label, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.label = label;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(label + ": " + getCurrentSeconds() + "s"));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentSeconds());
            updateMessage();
        }

        private int getCurrentSeconds() {
            int raw = (int) Math.round(this.value * (MAX_SECONDS - MIN_SECONDS)) + MIN_SECONDS;
            return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, raw));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
            return (clamped - MIN_SECONDS) / (double) (MAX_SECONDS - MIN_SECONDS);
        }
    }

    /** Slider for auto-clip duration (5-300 seconds). */
    private static final class AutoClipDurationSlider extends SliderWidget {
        private static final int MIN_SECONDS = 5;
        private static final int MAX_SECONDS = 300;
        private final IntConsumer setter;
        private final RecordableConfig config;
        private boolean firstInteraction = true;

        private AutoClipDurationSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter, RecordableConfig config) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            this.config = config;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int seconds = getCurrentSeconds();
            String label = seconds >= 60
                    ? String.format("Auto-Clip Duration: %dm %ds", seconds / 60, seconds % 60)
                    : "Auto-Clip Duration: " + seconds + "s";
            this.setMessage(Text.literal(label));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentSeconds());
            
            // Auto-enable achievement trigger if no triggers are enabled
            if (firstInteraction && config != null) {
                firstInteraction = false;
                if (!config.autoClipEnabled) {
                    config.autoClipEnabled = true;
                    config.autoClipOnAchievement = true;
                    config.autoClipOnDeath = true;
                    config.autoClipOnDimensionChange = true;
                    config.autoClipOnBossKill = true;
                    config.autoClipOnKill = true;
                    config.autoClipOnPlayerKill = true;
                    config.autoClipOnTotemPop = true;
                    RecordableMod.LOGGER.info("Auto-enabled auto-clipping when adjusting auto-clip duration");
                }
            }
            
            updateMessage();
        }

        private int getCurrentSeconds() {
            int raw = (int) Math.round(this.value * (MAX_SECONDS - MIN_SECONDS)) + MIN_SECONDS;
            int snapped = Math.round(raw / 5.0F) * 5;
            return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, snapped));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
            return (clamped - MIN_SECONDS) / (double) (MAX_SECONDS - MIN_SECONDS);
        }
    }

    /**
     * Slider for the auto-clip / kill-montage capture frame-rate. Independent of the main
     * recording FPS slider; snaps to {@link RecordableConfig#AUTO_CLIP_FPS_VALUES}.
     */
    private static final class AutoClipFpsSlider extends SliderWidget {
        private final IntConsumer setter;

        private AutoClipFpsSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalizeFps(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.translatable("screen.recordable.settings.autoclip_fps", getCurrentFps()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentFps());
            updateMessage();
        }

        private int getCurrentFps() {
            int index = (int) Math.round(this.value * (RecordableConfig.AUTO_CLIP_FPS_VALUES.length - 1));
            index = Math.max(0, Math.min(RecordableConfig.AUTO_CLIP_FPS_VALUES.length - 1, index));
            return RecordableConfig.AUTO_CLIP_FPS_VALUES[index];
        }

        private static double normalizeFps(int fps) {
            for (int index = 0; index < RecordableConfig.AUTO_CLIP_FPS_VALUES.length; index++) {
                if (RecordableConfig.AUTO_CLIP_FPS_VALUES[index] == fps) {
                    return index / (double) (RecordableConfig.AUTO_CLIP_FPS_VALUES.length - 1);
                }
            }
            return 0.6D;
        }
    }

    /** Slider for hindsight lookback duration (5-60 seconds). */
    private static final class AutoClipHindsightLookbackSlider extends SliderWidget {
        private static final int MIN_SECONDS = 5;
        private static final int MAX_SECONDS = 60;
        private final IntConsumer setter;

        private AutoClipHindsightLookbackSlider(int x, int y, int width, int height, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), normalize(currentValue));
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.translatable("screen.recordable.settings.autoclip_hindsight_lookback", getCurrentSeconds()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(getCurrentSeconds());
            updateMessage();
        }

        private int getCurrentSeconds() {
            int raw = (int) Math.round(this.value * (MAX_SECONDS - MIN_SECONDS)) + MIN_SECONDS;
            return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, raw));
        }

        private static double normalize(int value) {
            int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
            return (clamped - MIN_SECONDS) / (double) (MAX_SECONDS - MIN_SECONDS);
        }
    }

}
