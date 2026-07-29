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

    /**
     * ── Critical 1.20.0-1.20.4 rendering fix ──
     *
     * <p>In 1.20.2+, {@code Screen.render()} calls {@code renderBackground()}
     * itself. The previous implementation <i>also</i> called
     * {@code this.renderBackground()} manually before {@code super.render()},
     * which drew the dirt/panorama background <b>twice</b>.</p>
     *
     * <p>{@code DrawContext} batches everything into a single
     * {@code VertexConsumerProvider.Immediate} that is flushed once at the end of
     * the frame, and the immediate flushes its render layers in a <b>fixed
     * order</b> - the textured (dirt) layer flushes <i>after</i> the
     * {@code getGui()} layer used by {@code fill()}. So the second dirt draw
     * (issued by {@code super.render()} → {@code Screen.render()} →
     * {@code renderBackground()}) ended up painted <b>over</b> every panel/swatch
     * fill we had drawn in between, while the text layer (flushed last) still
     * showed. That is exactly why all the {@code fill()}-based panels and the
     * color-palette swatches were invisible while every label rendered fine.</p>
     *
     * <p>The fix: draw the dirt background <b>exactly once</b>, force a
     * {@code context.draw()} to rasterize it to the framebuffer, and only then
     * draw all themed fills - so no later dirt draw can cover them. We do this by
     * overriding {@code renderBackground()} (which {@code Screen.render()} invokes
     * before it renders the widgets), and reducing {@code render()} to a single
     * {@code super.render()} call.</p>
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1) Vanilla background (dirt on the menu, or the in-world dim gradient).
        super.renderBackground(context, mouseX, mouseY, delta);
        // 2) Commit it to the framebuffer NOW so the textured background layer
        //    cannot be re-ordered on top of the themed fills below.
        context.draw();

        // 3) All themed fill-based decorations + text, drawn after the flush so
        //    they land on top of the rasterized background. These are drawn
        //    BEFORE the widgets (Screen.render() renders widgets after calling
        //    renderBackground), so the control buttons still sit on top of the
        //    left panel.
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

        // Right panel: live preview (its panel background + color swatches).
        // No widgets overlap this region, so it is safe to draw here.
        renderPreview(context);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.render() calls renderBackground() (our override, which draws the
        // dirt once, flushes it, then paints all themed panels/swatches), then
        // renders the widgets (buttons) on top.
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

        // Read all keybinds live each frame so user rebinds show up immediately.
        Hotkey[] hotkeys = {
                Hotkey.TOGGLE_RECORDING,
                Hotkey.PAUSE_RESUME,
                Hotkey.OPEN_SETTINGS,
                Hotkey.OPEN_VIDEOS,
                Hotkey.ADD_BOOKMARK,
                Hotkey.PUSH_TO_TALK,
                Hotkey.SAVE_REPLAY_BUFFER,
                Hotkey.CANCEL_RECORDING,
                Hotkey.NAME_RECORDING,
                Hotkey.OPEN_RECORDING_SETTINGS,
                Hotkey.TOGGLE_CENSOR_OVERLAY,
                Hotkey.OPEN_CENSOR_EDITOR,
                Hotkey.OPEN_PENDING_RENDERS
        };
        String[] keyDescs = {
                "Record",
                "Pause",
                "Settings",
                "Video List",
                "Bookmark",
                "Push To Talk",
                "Save Replay",
                "Cancel Recording",
                "Name Recording",
                "Recording Settings",
                "Toggle Censor",
                "Censor Editor",
                "Pending Renders"
        };

        String[] keyLabels = new String[hotkeys.length];
        int maxLabelW = 0;
        for (int i = 0; i < hotkeys.length; i++) {
            keyLabels[i] = RecordableModInit.getBoundKeyDisplay(hotkeys[i]);
            maxLabelW = Math.max(maxLabelW, this.textRenderer.getWidth(keyLabels[i]));
        }

        int badgeH = 12;
        int columnGap = 8;
        int columnCount = 2;
        int columnWidth = (pw - 20 - columnGap) / columnCount;
        int badgeW = Math.min(maxLabelW + 8, Math.max(40, columnWidth - 62));
        int rowHeight = badgeH + 4;

        for (int i = 0; i < keyLabels.length; i++) {
            int col = i % columnCount;
            int row = i / columnCount;
            int bx = px + 10 + col * (columnWidth + columnGap);
            int by = py + row * rowHeight;

            context.fill(bx, by, bx + badgeW, by + badgeH, colors.buttonBackground);
            context.fill(bx, by, bx + badgeW, by + 1, colors.buttonBorder);
            context.fill(bx, by + badgeH - 1, bx + badgeW, by + badgeH, colors.buttonBorder);
            context.fill(bx, by, bx + 1, by + badgeH, colors.buttonBorder);
            context.fill(bx + badgeW - 1, by, bx + badgeW, by + badgeH, colors.buttonBorder);

            int keyTextW = this.textRenderer.getWidth(keyLabels[i]);
            int keyColor = "Not Bound".equals(keyLabels[i]) ? colors.textMuted : colors.accent;
            RenderHelper.drawText(context, this.textRenderer, keyLabels[i],
                    bx + (badgeW - keyTextW) / 2, by + 2, keyColor);
            int descX = bx + badgeW + 5;
            int descMaxW = Math.max(24, previewRight - descX - 8);
            RenderHelper.drawText(context, this.textRenderer, fitText(keyDescs[i], descMaxW), descX, by + 2, colors.textPrimary);
        }

        int rows = (keyLabels.length + columnCount - 1) / columnCount;
        py += rows * rowHeight + 6;

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
