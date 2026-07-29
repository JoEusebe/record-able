package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedSlider;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * V1-0.09 Export Settings (dedicated export screen).
 * 
 * Provides fine-grained control over export format, codecs, bitrates,
 * resolution and FPS for post-recording encoding.
 */
public final class ExportSettingsScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_W = 400;
    
    private static final String[] EXPORT_FORMATS = {"mp4", "mkv", "mov", "avi", "webm"};
    private static final String[] VIDEO_CODECS = {"h264", "h265", "vp9"};
    private static final String[] AUDIO_CODECS = {"aac", "mp3", "opus"};
    private static final String[] EXPORT_RESOLUTIONS = {"native", "1080p", "720p", "480p"};
    private static final int[] EXPORT_FPS_VALUES = {0, 24, 30, 60, 120};

    private final Screen parent;
    private int panelX, panelY, panelBottom;

    public ExportSettingsScreen(Screen parent) {
        super(Component.literal("Export Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { onClose(); return; }

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = 30;
        this.panelBottom = this.height - 20;

        int innerW = PANEL_W - 24;
        int gap = 8;
        int halfW = (innerW - gap) / 2;
        int colL = this.panelX + 12;
        int colR = colL + halfW + gap;
        int y = this.panelY + 34;

        // Row 1: Format
        var formatButton = CycleButton.create(colL, y, innerW, WIDGET_HEIGHT,
                Component.literal("Format: " + getFormatDisplay(config)),
                b -> {
            config.exportFormat = nextFormat(config.exportFormat);
            config.save();
            b.setMessage(Component.literal("Format: " + getFormatDisplay(config)));
        }, b -> {
            config.exportFormat = prevFormat(config.exportFormat);
            config.save();
            b.setMessage(Component.literal("Format: " + getFormatDisplay(config)));
        });
        addRenderableWidget(formatButton);
        y += ROW_SPACING;

        // Row 2: Video Codec + Bitrate
        var videoCodecButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                Component.literal("Codec: " + getVideoCodecDisplay(config)),
                b -> {
            config.exportVideoCodec = nextVideoCodec(config.exportVideoCodec);
            config.save();
            b.setMessage(Component.literal("Codec: " + getVideoCodecDisplay(config)));
        }, b -> {
            config.exportVideoCodec = prevVideoCodec(config.exportVideoCodec);
            config.save();
            b.setMessage(Component.literal("Codec: " + getVideoCodecDisplay(config)));
        });
        addRenderableWidget(videoCodecButton);

        var videoBitrateSlider = new ThemedSlider(colR, y, halfW, WIDGET_HEIGHT,
                "Bitrate: %d Mbps", 0, 50, config.exportVideoBitrateMbps,
                v -> {
                    config.exportVideoBitrateMbps = v.intValue();
                    config.save();
                });
        addRenderableWidget(videoBitrateSlider);
        y += ROW_SPACING;

        // Row 3: Audio Codec + Bitrate
        var audioCodecButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                Component.literal("Audio: " + getAudioCodecDisplay(config)),
                b -> {
            config.exportAudioCodec = nextAudioCodec(config.exportAudioCodec);
            config.save();
            b.setMessage(Component.literal("Audio: " + getAudioCodecDisplay(config)));
        }, b -> {
            config.exportAudioCodec = prevAudioCodec(config.exportAudioCodec);
            config.save();
            b.setMessage(Component.literal("Audio: " + getAudioCodecDisplay(config)));
        });
        addRenderableWidget(audioCodecButton);

        var audioBitrateSlider = new ThemedSlider(colR, y, halfW, WIDGET_HEIGHT,
                "ABR: %d kbps", 0, 320, config.exportAudioBitrateKbps,
                v -> {
                    config.exportAudioBitrateKbps = v.intValue();
                    config.save();
                });
        addRenderableWidget(audioBitrateSlider);
        y += ROW_SPACING;

        // Row 4: Resolution + FPS
        var resolutionButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                Component.literal("Res: " + getResolutionDisplay(config)),
                b -> {
            config.exportResolution = nextResolution(config.exportResolution);
            config.save();
            b.setMessage(Component.literal("Res: " + getResolutionDisplay(config)));
        }, b -> {
            config.exportResolution = prevResolution(config.exportResolution);
            config.save();
            b.setMessage(Component.literal("Res: " + getResolutionDisplay(config)));
        });
        addRenderableWidget(resolutionButton);

        var fpsButton = CycleButton.create(colR, y, halfW, WIDGET_HEIGHT,
                Component.literal("FPS: " + getFpsDisplay(config)),
                b -> {
            config.exportFps = nextFps(config.exportFps);
            config.save();
            b.setMessage(Component.literal("FPS: " + getFpsDisplay(config)));
        }, b -> {
            config.exportFps = prevFps(config.exportFps);
            config.save();
            b.setMessage(Component.literal("FPS: " + getFpsDisplay(config)));
        });
        addRenderableWidget(fpsButton);
        y += ROW_SPACING + 10;

        // Reset button
        var resetButton = ThemedButton.create(colL, y, innerW, WIDGET_HEIGHT,
                Component.literal("Reset to Defaults"), b -> {
            config.exportFormat = "";
            config.exportVideoCodec = "";
            config.exportVideoBitrateMbps = 0;
            config.exportAudioCodec = "";
            config.exportAudioBitrateKbps = 0;
            config.exportResolution = "";
            config.exportFps = 0;
            config.save();
            if (this.minecraft != null) this.minecraft.setScreenAndShow(new ExportSettingsScreen(parent));
        });
        addRenderableWidget(resetButton);
        y += ROW_SPACING;

        // Close button
        var closeButton = ThemedButton.create(colL, this.height - 40, innerW, WIDGET_HEIGHT,
                Component.literal("Done"), b -> onClose());
        addRenderableWidget(closeButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        
        // Title
        context.centeredText(this.font, this.title, this.width / 2, this.panelY + 10, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    private String getFormatDisplay(RecordableConfig cfg) {
        return cfg.exportFormat.isEmpty() ? "Auto" : cfg.exportFormat.toUpperCase();
    }

    private String getVideoCodecDisplay(RecordableConfig cfg) {
        return cfg.exportVideoCodec.isEmpty() ? "Auto" : cfg.exportVideoCodec.toUpperCase();
    }

    private String getAudioCodecDisplay(RecordableConfig cfg) {
        return cfg.exportAudioCodec.isEmpty() ? "Auto" : cfg.exportAudioCodec.toUpperCase();
    }

    private String getResolutionDisplay(RecordableConfig cfg) {
        return cfg.exportResolution.isEmpty() ? "Recording" : cfg.exportResolution;
    }

    private String getFpsDisplay(RecordableConfig cfg) {
        return cfg.exportFps == 0 ? "Recording" : String.valueOf(cfg.exportFps);
    }

    private String nextFormat(String current) {
        if (current.isEmpty()) return EXPORT_FORMATS[0];
        for (int i = 0; i < EXPORT_FORMATS.length; i++) {
            if (EXPORT_FORMATS[i].equals(current)) {
                return i == EXPORT_FORMATS.length - 1 ? "" : EXPORT_FORMATS[i + 1];
            }
        }
        return EXPORT_FORMATS[0];
    }

    private String prevFormat(String current) {
        if (current.isEmpty()) return EXPORT_FORMATS[EXPORT_FORMATS.length - 1];
        for (int i = 0; i < EXPORT_FORMATS.length; i++) {
            if (EXPORT_FORMATS[i].equals(current)) {
                return i == 0 ? "" : EXPORT_FORMATS[i - 1];
            }
        }
        return EXPORT_FORMATS[0];
    }

    private String nextVideoCodec(String current) {
        if (current.isEmpty()) return VIDEO_CODECS[0];
        for (int i = 0; i < VIDEO_CODECS.length; i++) {
            if (VIDEO_CODECS[i].equals(current)) {
                return i == VIDEO_CODECS.length - 1 ? "" : VIDEO_CODECS[i + 1];
            }
        }
        return VIDEO_CODECS[0];
    }

    private String prevVideoCodec(String current) {
        if (current.isEmpty()) return VIDEO_CODECS[VIDEO_CODECS.length - 1];
        for (int i = 0; i < VIDEO_CODECS.length; i++) {
            if (VIDEO_CODECS[i].equals(current)) {
                return i == 0 ? "" : VIDEO_CODECS[i - 1];
            }
        }
        return VIDEO_CODECS[0];
    }

    private String nextAudioCodec(String current) {
        if (current.isEmpty()) return AUDIO_CODECS[0];
        for (int i = 0; i < AUDIO_CODECS.length; i++) {
            if (AUDIO_CODECS[i].equals(current)) {
                return i == AUDIO_CODECS.length - 1 ? "" : AUDIO_CODECS[i + 1];
            }
        }
        return AUDIO_CODECS[0];
    }

    private String prevAudioCodec(String current) {
        if (current.isEmpty()) return AUDIO_CODECS[AUDIO_CODECS.length - 1];
        for (int i = 0; i < AUDIO_CODECS.length; i++) {
            if (AUDIO_CODECS[i].equals(current)) {
                return i == 0 ? "" : AUDIO_CODECS[i - 1];
            }
        }
        return AUDIO_CODECS[0];
    }

    private String nextResolution(String current) {
        if (current.isEmpty()) return EXPORT_RESOLUTIONS[0];
        for (int i = 0; i < EXPORT_RESOLUTIONS.length; i++) {
            if (EXPORT_RESOLUTIONS[i].equals(current)) {
                return i == EXPORT_RESOLUTIONS.length - 1 ? "" : EXPORT_RESOLUTIONS[i + 1];
            }
        }
        return EXPORT_RESOLUTIONS[0];
    }

    private String prevResolution(String current) {
        if (current.isEmpty()) return EXPORT_RESOLUTIONS[EXPORT_RESOLUTIONS.length - 1];
        for (int i = 0; i < EXPORT_RESOLUTIONS.length; i++) {
            if (EXPORT_RESOLUTIONS[i].equals(current)) {
                return i == 0 ? "" : EXPORT_RESOLUTIONS[i - 1];
            }
        }
        return EXPORT_RESOLUTIONS[0];
    }

    private int nextFps(int current) {
        for (int i = 0; i < EXPORT_FPS_VALUES.length; i++) {
            if (EXPORT_FPS_VALUES[i] == current) {
                return i == EXPORT_FPS_VALUES.length - 1 ? EXPORT_FPS_VALUES[0] : EXPORT_FPS_VALUES[i + 1];
            }
        }
        return EXPORT_FPS_VALUES[0];
    }

    private int prevFps(int current) {
        for (int i = 0; i < EXPORT_FPS_VALUES.length; i++) {
            if (EXPORT_FPS_VALUES[i] == current) {
                return i == 0 ? EXPORT_FPS_VALUES[EXPORT_FPS_VALUES.length - 1] : EXPORT_FPS_VALUES[i - 1];
            }
        }
        return EXPORT_FPS_VALUES[0];
    }
}
