package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableModInit;
import dev.recordable.RecordableModInit.Hotkey;
import dev.recordable.theme.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Theme customization screen with live preview of all theme effects.
 * Allows users to select presets and toggle individual visual effects.
 */
public final class ThemeSettingsScreen extends Screen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 24;

    private final Screen parent;
    private TypewriterText titleAnim;
    private int panelLeft, panelTop, panelWidth, panelBottom;
    private int previewLeft, previewTop, previewRight, previewBottom;
    private int descriptionY; // y position for theme description text

    public ThemeSettingsScreen(Screen parent) {
        super(Text.translatable("screen.recordable.theme.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearChildren();

        RecordableConfig config = RecordableConfig.get();
        if (config == null) {
            close();
            return;
        }

        this.titleAnim = new TypewriterText("[ THEME CONFIGURATION ]", 30);

        // Layout: left side = controls, right side = preview
        int totalWidth = Math.max(500, Math.min((int)(this.width * 0.88), 800));
        int totalLeft = (this.width - totalWidth) / 2;
        this.panelWidth = totalWidth / 2 - 8;
        this.panelLeft = totalLeft;
        this.panelTop = Math.max(24, (int)(this.height * 0.08));
        this.panelBottom = this.height - 36;

        this.previewLeft = totalLeft + this.panelWidth + 16;
        this.previewTop = this.panelTop;
        this.previewRight = totalLeft + totalWidth;
        this.previewBottom = this.panelBottom;

        int wLeft = this.panelLeft + 10;
        int wWidth = this.panelWidth - 20;
        int y = this.panelTop + 22;

        // Theme preset cycle button
        addDrawableChild(CycleButton.create(wLeft, y, wWidth, WIDGET_HEIGHT,
                Text.literal("Theme: " + config.uiTheme.displayName),
                button -> {
                    config.uiTheme = config.uiTheme.next();
                    ThemeEngine.get().applyPreset(config.uiTheme);
                    config.save();
                    if (this.client != null) {
                        this.client.setScreen(new ThemeSettingsScreen(this.parent));
                    }
                }, button -> {
                    config.uiTheme = config.uiTheme.prev();
                    ThemeEngine.get().applyPreset(config.uiTheme);
                    config.save();
                    if (this.client != null) {
                        this.client.setScreen(new ThemeSettingsScreen(this.parent));
                    }
                }));
        y += WIDGET_HEIGHT + 4; // just below the button

        // Description text position (rendered in render())
        this.descriptionY = y;
        y += 24; // space for two description lines + gap before toggles

        // Effect toggles are dynamic per-theme.
        for (ThemePreset.ThemeToggle toggle : config.uiTheme.toggles()) {
            addThemeToggle(wLeft, y, wWidth, config, toggle);
            y += ROW_SPACING;
        }
        if (config.uiTheme == ThemePreset.GALAXY) {
            addDrawableChild(CycleButton.create(wLeft, y, wWidth, WIDGET_HEIGHT,
                    Text.literal(galaxyGlowButtonLabel(config.uiGalaxyGlowIntensity)),
                    button -> {
                        config.uiGalaxyGlowIntensity = cycleGalaxyGlow(config.uiGalaxyGlowIntensity, true);
                        ThemeEngine.get().loadFromConfig();
                        config.save();
                        button.setMessage(Text.literal(galaxyGlowButtonLabel(config.uiGalaxyGlowIntensity)));
                    }, button -> {
                        config.uiGalaxyGlowIntensity = cycleGalaxyGlow(config.uiGalaxyGlowIntensity, false);
                        ThemeEngine.get().loadFromConfig();
                        config.save();
                        button.setMessage(Text.literal(galaxyGlowButtonLabel(config.uiGalaxyGlowIntensity)));
                    }));
            y += ROW_SPACING;
        }
        y += 8;

        // Reset to defaults
        addDrawableChild(ThemedButton.create(wLeft, y, wWidth, WIDGET_HEIGHT,
                Text.literal("Reset to Defaults"),
                button -> {
                    config.uiTheme = ThemePreset.VHS;
                    config.uiScanlines = true;
                    config.uiFilmGrain = true;
                    config.uiGlitchEffects = true;
                    config.uiVignette = true;
                    config.uiAnimations = true;
                    config.uiGalaxyGlowIntensity = 1;
                    config.uiCustomAccentColor = "";
                    ThemeEngine.get().loadFromConfig();
                    config.save();
                    if (this.client != null) {
                        this.client.setScreen(new ThemeSettingsScreen(this.parent));
                    }
                }));

        // Done button at bottom
        addDrawableChild(ThemedButton.create(
                (this.width - 120) / 2, this.panelBottom + 6, 120, WIDGET_HEIGHT,
                Text.literal("Done"),
                button -> close()));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        ThemeColors colors = ThemeEngine.get().colors();

        // Left panel: controls
        ThemedPanel.drawPanel(context, panelLeft - 4, panelTop - 4, panelLeft + panelWidth + 4, panelBottom);

        // Title with typewriter effect
        if (titleAnim != null) {
            titleAnim.render(context, this.textRenderer, panelLeft + 10, panelTop + 8, colors.headerText);
        }

        // Theme description (positioned dynamically below the theme button)
        ThemePreset preset = ThemeEngine.get().preset();
        int descMaxW = Math.max(40, this.panelWidth - 20);
        String description = preset.description == null ? "" : preset.description;
        String line1 = fitText(description, descMaxW);
        RenderHelper.drawText(context, this.textRenderer, line1, panelLeft + 10, this.descriptionY, colors.textMuted);
        String remainder = description.length() > line1.length() ? description.substring(line1.length()).trim() : "";
        if (!remainder.isEmpty()) {
            RenderHelper.drawText(context, this.textRenderer, fitText(remainder, descMaxW), panelLeft + 10, this.descriptionY + 10, colors.textMuted);
        }

        // Right panel: live preview
        renderPreview(context);

        super.render(context, mouseX, mouseY, delta);
    }

    /** Encoder display name for the preview panel, read live from config. */
    private static String previewEncoderName() {
        try {
            RecordableConfig c = RecordableConfig.get();
            if (c != null && c.encoder != null) {
                // For SOFTWARE, show the concrete codec that will actually run
                // (e.g. "Software (MPEG-4)") rather than the generic "Software (x264)"
                // label, which is misleading on Android where libx264 is absent.
                if (c.encoder == RecordableConfig.VideoEncoder.SOFTWARE) {
                    String codec = dev.recordable.FFmpegEncoder.getCachedSoftwareCodec();
                    if (codec != null) {
                        return "Software (" + prettyCodecName(codec) + ")";
                    }
                }
                return c.encoder.displayName;
            }
        } catch (Throwable ignored) {
        }
        return RecordableConfig.VideoEncoder.SOFTWARE.displayName;
    }

    /** Human-friendly label for an FFmpeg codec string. */
    private static String prettyCodecName(String codec) {
        if (codec == null) {
            return "?";
        }
        switch (codec) {
            case "libx264": return "x264";
            case "libx265": return "x265";
            case "mpeg4": return "MPEG-4";
            case "libxvid": return "Xvid";
            case "h264_mediacodec": return "HW H.264";
            default: return codec;
        }
    }

    /** Resolution label for the preview panel, read live from config. */
    private static String previewResolution() {
        try {
            RecordableConfig c = RecordableConfig.get();
            if (c != null && c.resolution != null && !c.resolution.isBlank()) {
                return c.resolution;
            }
        } catch (Throwable ignored) {
        }
        return "1080p";
    }

    /** Frame-rate value for the preview panel, read live from config. */
    private static int previewFps() {
        try {
            RecordableConfig c = RecordableConfig.get();
            if (c != null && c.fps > 0) {
                return c.fps;
            }
        } catch (Throwable ignored) {
        }
        return 60;
    }

    private void addThemeToggle(int x, int y, int width, RecordableConfig config, ThemePreset.ThemeToggle toggle) {
        addDrawableChild(ThemedToggle.create(x, y, width, WIDGET_HEIGHT,
                toggle.label, getThemeToggleValue(config, toggle), val -> {
            setThemeToggleValue(config, toggle, val);
            ThemeEngine.get().loadFromConfig();
            config.save();
        }));
    }

    private static boolean getThemeToggleValue(RecordableConfig config, ThemePreset.ThemeToggle toggle) {
        return switch (toggle) {
            case SCANLINES -> config.uiScanlines;
            case FILM_GRAIN -> config.uiFilmGrain;
            case GLITCH_EFFECTS -> config.uiGlitchEffects;
            case VIGNETTE -> config.uiVignette;
            case ANIMATIONS -> config.uiAnimations;
        };
    }

    private static void setThemeToggleValue(RecordableConfig config, ThemePreset.ThemeToggle toggle, boolean value) {
        switch (toggle) {
            case SCANLINES -> config.uiScanlines = value;
            case FILM_GRAIN -> config.uiFilmGrain = value;
            case GLITCH_EFFECTS -> config.uiGlitchEffects = value;
            case VIGNETTE -> config.uiVignette = value;
            case ANIMATIONS -> config.uiAnimations = value;
        }
    }

    private static int cycleGalaxyGlow(int current, boolean forward) {
        int normalized = Math.max(0, Math.min(2, current));
        return (normalized + (forward ? 1 : 2)) % 3;
    }

    private static String galaxyGlowButtonLabel(int intensity) {
        return "Galaxy Glow: " + switch (Math.max(0, Math.min(2, intensity))) {
            case 0 -> "Low";
            case 2 -> "High";
            default -> "Medium";
        };
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || maxWidth <= 4) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        return this.textRenderer.trimToWidth(text, maxWidth);
    }

    private void renderPreview(DrawContext context) {
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();

        // Choose panel style based on theme
        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(context, previewLeft, previewTop, previewRight, previewBottom);
        } else {
            ThemedPanel.drawPanel(context, previewLeft, previewTop, previewRight, previewBottom);
        }

        int px = previewLeft + 14;
        int pw = previewRight - previewLeft - 28;
        int py = previewTop + 12;
        int panelH = previewBottom - previewTop;

        // ═══════════════════════════════════════════
        // Section 1: Title
        // ═══════════════════════════════════════════
        ThemedPanel.drawSectionHeader(context, this.textRenderer, "Live Preview", px, py, pw);
        py += 22;

        // ═══════════════════════════════════════════
        // Section 2: Sample settings text
        // ═══════════════════════════════════════════
        ThemedPanel.drawSectionHeader(context, this.textRenderer, "Recording Settings", px, py, pw);
        py += 18;

        RenderHelper.drawText(context, this.textRenderer, "Resolution: " + previewResolution(), px + 8, py, colors.textPrimary);
        py += 12;
        RenderHelper.drawText(context, this.textRenderer, "FPS: " + previewFps(), px + 8, py, colors.textPrimary);
        py += 12;
        RenderHelper.drawText(context, this.textRenderer, "Encoder: " + previewEncoderName(), px + 8, py, colors.textSecondary);
        py += 18;

        // ─── Divider ───
        ThemedPanel.drawDivider(context, px, py, pw);
        py += 14;

        // ═══════════════════════════════════════════
        // Section 3: Quick Keys - live keybind reminders
        // ═══════════════════════════════════════════
        RenderHelper.drawText(context, this.textRenderer, "Quick Keys:", px + 6, py, colors.textMuted);
        py += 14;

        // Read the actual bound keys live each frame so user rebinds show up
        // immediately (and "Not Bound" actions are reported correctly).
        String[] keyLabels = {
            RecordableModInit.getBoundKeyDisplay(Hotkey.TOGGLE_RECORDING),
            RecordableModInit.getBoundKeyDisplay(Hotkey.PAUSE_RESUME),
            RecordableModInit.getBoundKeyDisplay(Hotkey.OPEN_SETTINGS),
            RecordableModInit.getBoundKeyDisplay(Hotkey.OPEN_VIDEOS),
            RecordableModInit.getBoundKeyDisplay(Hotkey.ADD_BOOKMARK),
            RecordableModInit.getBoundKeyDisplay(Hotkey.PUSH_TO_TALK),
            RecordableModInit.getBoundKeyDisplay(Hotkey.SAVE_REPLAY_BUFFER),
            RecordableModInit.getBoundKeyDisplay(Hotkey.CANCEL_RECORDING),
            RecordableModInit.getBoundKeyDisplay(Hotkey.NAME_RECORDING),
            RecordableModInit.getBoundKeyDisplay(Hotkey.OPEN_RECORDING_SETTINGS),
            RecordableModInit.getBoundKeyDisplay(Hotkey.TOGGLE_CENSOR_OVERLAY),
            RecordableModInit.getBoundKeyDisplay(Hotkey.OPEN_CENSOR_EDITOR),
            RecordableModInit.getBoundKeyDisplay(Hotkey.OPEN_PENDING_RENDERS)
        };
        String[] keyDescs = {
            "Record", "Pause", "Settings", "Video List", "Bookmark", "Push To Talk",
            "Save Replay", "Cancel Recording", "Name Recording", "Recording Settings",
            "Toggle Censor", "Censor Editor", "Pending Renders"
        };

        // Align all description columns to a common x based on the widest badge.
        int badgeH = 12;
        int maxLabelW = 0;
        for (String label : keyLabels) {
            maxLabelW = Math.max(maxLabelW, this.textRenderer.getWidth(label));
        }
        int badgeW = maxLabelW + 8;
        int bx = px + 10;
        for (int k = 0; k < keyLabels.length; k++) {
            // Draw key badge (fixed width so the labels beside them line up)
            context.fill(bx, py, bx + badgeW, py + badgeH, colors.buttonBackground);
            context.fill(bx, py, bx + badgeW, py + 1, colors.buttonBorder);
            context.fill(bx, py + badgeH - 1, bx + badgeW, py + badgeH, colors.buttonBorder);
            context.fill(bx, py, bx + 1, py + badgeH, colors.buttonBorder);
            context.fill(bx + badgeW - 1, py, bx + badgeW, py + badgeH, colors.buttonBorder);
            // Center the key text within the badge; dim it when unbound
            int keyTextW = this.textRenderer.getWidth(keyLabels[k]);
            int keyColor = "Not Bound".equals(keyLabels[k]) ? colors.textMuted : colors.accent;
            RenderHelper.drawText(context, this.textRenderer, keyLabels[k],
                    bx + (badgeW - keyTextW) / 2, py + 2, keyColor);
            // Description text beside badge
            int descX = bx + badgeW + 6;
            int descMaxW = Math.max(24, previewRight - descX - 8);
            RenderHelper.drawText(context, this.textRenderer, fitText(keyDescs[k], descMaxW), descX, py + 2, colors.textPrimary);
            py += badgeH + 4;
        }
        py += 6;

        // ─── Divider ───
        ThemedPanel.drawDivider(context, px, py, pw);
        py += 14;

    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
