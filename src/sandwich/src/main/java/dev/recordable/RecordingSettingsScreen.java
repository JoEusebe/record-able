package dev.recordable;

import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedPanel;
import dev.recordable.theme.ThemedToggle;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

/**
 * In-game configuration screen for the deferred (offline smooth-render) system.
 *
 * <p>Deferred rendering captures raw frames at a low FPS during gameplay to keep
 * the game smooth on low-end PCs, then renders a high-FPS video after the session
 * ends. This screen exposes every deferred option so users no longer need to edit
 * the config file by hand.</p>
 *
 * <p>Changes are held in a working copy and only written to disk when the user
 * presses "Save and Close". Pressing "Cancel" (or Escape) discards them.</p>
 */
public final class RecordingSettingsScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING   = 24;
    private static final int PANEL_W       = 360;

    private final Screen parent;

    // Working copy of the settings (applied only on Save).
    private boolean deferredEnabled;
    private int     captureFps;
    private int     targetFps;
    private String  interpolation;
    private boolean autoRender;
    private boolean keepTempFrames;

    private int panelX, panelY, panelBottom;
    private int infoY;

    public RecordingSettingsScreen(Screen parent) {
        super(Text.literal("Recording Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { close(); return; }

        // Load current values into the working copy.
        this.deferredEnabled = config.deferredRenderEnabled;
        this.captureFps      = config.deferredCaptureFps;
        this.targetFps       = config.deferredTargetFps;
        this.interpolation   = sanitizeInterpolation(config.deferredInterpolation);
        this.autoRender      = config.deferredAutoRender;
        this.keepTempFrames  = config.deferredKeepTempFrames;

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = 30;
        this.panelBottom = this.height - 20;

        int innerW = PANEL_W - 24;
        int gap = 8;
        int halfW = (innerW - gap) / 2;
        int colL = this.panelX + 12;
        int colR = colL + halfW + gap;
        int y = this.panelY + 30;

        // Row 1: master toggle (full width) ---------------------------------
        var deferredToggle = ThemedToggle.create(colL, y, innerW, WIDGET_HEIGHT,
                "Deferred Rendering", this.deferredEnabled, v -> this.deferredEnabled = v);
        deferredToggle.setTooltip(tip("Master switch for offline smooth-render. When ON, gameplay is captured at a "
                + "low frame rate (easy on the GPU) and a smooth high-FPS video is produced after you stop recording. "
                + "Best for low-end PCs that drop frames while recording live."));
        addDrawableChild(deferredToggle);
        y += ROW_SPACING;

        // Row 2: capture fps + target fps -----------------------------------
        var captureFpsButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                captureFpsLabel(),
                b -> { this.captureFps = cycle(RecordableConfig.DEFERRED_CAPTURE_FPS, this.captureFps, true);
                       b.setMessage(captureFpsLabel()); },
                b -> { this.captureFps = cycle(RecordableConfig.DEFERRED_CAPTURE_FPS, this.captureFps, false);
                       b.setMessage(captureFpsLabel()); });
        captureFpsButton.setTooltip(tip("How many frames per second are captured during recording (5-30). "
                + "Lower values put less load on your PC while playing but store fewer real frames. "
                + "Left-click cycles up, right-click cycles down."));
        addDrawableChild(captureFpsButton);

        var targetFpsButton = CycleButton.create(colR, y, halfW, WIDGET_HEIGHT,
                targetFpsLabel(),
                b -> { this.targetFps = cycle(RecordableConfig.DEFERRED_TARGET_FPS, this.targetFps, true);
                       b.setMessage(targetFpsLabel()); },
                b -> { this.targetFps = cycle(RecordableConfig.DEFERRED_TARGET_FPS, this.targetFps, false);
                       b.setMessage(targetFpsLabel()); });
        targetFpsButton.setTooltip(tip("The frame rate of the final rendered video (30, 60 or 120). "
                + "The extra frames between captured ones are created by the interpolation method below."));
        addDrawableChild(targetFpsButton);
        y += ROW_SPACING;

        // Row 3: interpolation + auto-render --------------------------------
        var interpButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                interpolationLabel(),
                b -> { this.interpolation = cycle(RecordableConfig.DEFERRED_INTERPOLATION, this.interpolation, true);
                       b.setMessage(interpolationLabel()); },
                b -> { this.interpolation = cycle(RecordableConfig.DEFERRED_INTERPOLATION, this.interpolation, false);
                       b.setMessage(interpolationLabel()); });
        interpButton.setTooltip(tip("How extra frames are generated when rendering to the target FPS. "
                + "None: no smoothing (choppy). Duplicate: repeats frames, fast and light. "
                + "Motion: estimates movement for the smoothest look, but is much slower to render."));
        addDrawableChild(interpButton);

        var autoRenderToggle = ThemedToggle.create(colR, y, halfW, WIDGET_HEIGHT,
                "Auto-Render", this.autoRender, v -> this.autoRender = v);
        autoRenderToggle.setTooltip(tip("When ON, the smooth video is rendered automatically as soon as you stop "
                + "recording. When OFF, a prompt lets you choose to render now, render later, or discard the capture."));
        addDrawableChild(autoRenderToggle);
        y += ROW_SPACING;

        // Row 4: keep temp frames (full width) ------------------------------
        var keepFramesToggle = ThemedToggle.create(colL, y, innerW, WIDGET_HEIGHT,
                "Keep Temp Frames", this.keepTempFrames, v -> this.keepTempFrames = v);
        keepFramesToggle.setTooltip(tip("When ON, the raw captured frames are kept on disk after a successful render "
                + "(useful for re-rendering at different settings). When OFF, they are deleted to save space. "
                + "Raw frames can use a lot of disk (hundreds of MB per minute)."));
        addDrawableChild(keepFramesToggle);
        y += ROW_SPACING;

        this.infoY = y + 6;

        // Bottom action buttons ---------------------------------------------
        int btnW = 130;
        int btnGap = 10;
        int totalW = btnW * 2 + btnGap;
        int startX = (this.width - totalW) / 2;
        int btnY = this.panelBottom - 28;

        addDrawableChild(ThemedButton.create(startX, btnY, btnW, WIDGET_HEIGHT,
                Text.literal("Save and Close"), b -> saveAndClose()));
        addDrawableChild(ThemedButton.create(startX + btnW + btnGap, btnY, btnW, WIDGET_HEIGHT,
                Text.literal("Cancel"), b -> close()));
    }

    private Text captureFpsLabel()   { return Text.literal("Capture FPS: " + this.captureFps); }
    private Text targetFpsLabel()    { return Text.literal("Target FPS: " + this.targetFps); }
    private Text interpolationLabel(){ return Text.literal("Interp: " + displayInterpolation(this.interpolation)); }

    private void saveAndClose() {
        RecordableConfig config = RecordableConfig.get();
        if (config != null) {
            config.deferredRenderEnabled = this.deferredEnabled;
            config.deferredCaptureFps    = this.captureFps;
            config.deferredTargetFps     = this.targetFps;
            config.deferredInterpolation = this.interpolation;
            config.deferredAutoRender    = this.autoRender;
            config.deferredKeepTempFrames = this.keepTempFrames;
            config.save();
        }
        close();
    }

    private static Tooltip tip(String s) {
        return Tooltip.of(Text.literal(s));
    }

    // --- Value cycling helpers --------------------------------------------

    private static int cycle(int[] values, int current, boolean forward) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                int next = forward ? (i + 1) % values.length : (i - 1 + values.length) % values.length;
                return values[next];
            }
        }
        return values.length > 0 ? values[0] : current;
    }

    private static String cycle(String[] values, String current, boolean forward) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                int next = forward ? (i + 1) % values.length : (i - 1 + values.length) % values.length;
                return values[next];
            }
        }
        return values.length > 0 ? values[0] : current;
    }

    private static String sanitizeInterpolation(String value) {
        for (String v : RecordableConfig.DEFERRED_INTERPOLATION) {
            if (v.equals(value)) return v;
        }
        return RecordableConfig.DEFERRED_INTERPOLATION[0];
    }

    private static String displayInterpolation(String value) {
        if (value == null || value.isEmpty()) return "None";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // --- Render ------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        ThemedPanel.drawPanel(context, panelX, panelY, panelX + PANEL_W, panelBottom);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelY + 12, 0xFFFFFFFF);

        int textX = panelX + 12;
        int line = 11;
        context.drawText(this.textRenderer,
                Text.literal("\u00a77Captures low-FPS frames while you play to keep FPS high,"),
                textX, infoY, 0xFFB0B0B0, false);
        context.drawText(this.textRenderer,
                Text.literal("\u00a77then renders a smooth video afterwards. Trade-off: uses"),
                textX, infoY + line, 0xFFB0B0B0, false);
        context.drawText(this.textRenderer,
                Text.literal("\u00a77disk space and extra time after recording."),
                textX, infoY + line * 2, 0xFFB0B0B0, false);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
