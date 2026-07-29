package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.SmoothMotion;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedToggle;
import dev.recordable.theme.ThemedPanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * V1-0.08 Performance category (modern variant).
 *
 * <p>Single home for every performance-related option in Record-able: the
 * one-click device preset tuner, smooth-motion recording, frame-buffer pooling,
 * the runtime performance optimizer and the on-screen performance stats.</p>
 */
public final class PerformanceScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING   = 24;
    private static final int PANEL_W       = 340;
    private static final int[] MIN_FPS_VALUES = {30, 45, 60, 90, 120};

    private final Screen parent;

    private int panelX, panelY, panelBottom;

    public PerformanceScreen(Screen parent) {
        super(Component.literal("Performance"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { onClose(); return; }

        // Preset selection persists so the button always reflects the current choice.
        config.selectedDevicePreset = RecordableConfig.sanitizeDevicePreset(config.selectedDevicePreset);

        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = 30;
        this.panelBottom = this.height - 20;

        int innerW = PANEL_W - 24;
        int gap = 8;
        int halfW = (innerW - gap) / 2;
        int colL = this.panelX + 12;
        int colR = colL + halfW + gap;
        int y = this.panelY + 34;

        // Row 1: device preset cycle + apply
        var presetButton = CycleButton.create(colL, y, halfW, WIDGET_HEIGHT,
                Component.literal("Preset: " + RecordableConfig.getDevicePresetDisplayName(config.selectedDevicePreset)),
                b -> {
            config.selectedDevicePreset = nextPreset(config.selectedDevicePreset);
            config.save();
            b.setMessage(Component.literal("Preset: " + RecordableConfig.getDevicePresetDisplayName(config.selectedDevicePreset)));
        }, b -> {
            config.selectedDevicePreset = prevPreset(config.selectedDevicePreset);
            config.save();
            b.setMessage(Component.literal("Preset: " + RecordableConfig.getDevicePresetDisplayName(config.selectedDevicePreset)));
        });
        presetButton.setTooltip(tip("Pick a one-click quality profile tuned for your device class (low-end, balanced, high-end and more). Left-click cycles forward, right-click goes back. Nothing is changed until you press Apply Preset."));
        addRenderableWidget(presetButton);
        var applyPresetButton = ThemedButton.create(colR, y, halfW, WIDGET_HEIGHT,
                Component.literal("Apply Preset"), b -> {
            try {
                config.applyDevicePreset(config.selectedDevicePreset);
                config.save();
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("Failed to apply device preset {}.", config.selectedDevicePreset, t);
            }
            if (this.minecraft != null) this.minecraft.setScreenAndShow(new PerformanceScreen(parent));
        });
        applyPresetButton.setTooltip(tip("Applies the selected device preset right now, overwriting your resolution, FPS, quality and related recording settings with values tuned for that device."));
        addRenderableWidget(applyPresetButton);
        y += ROW_SPACING;

        // Row 2: smooth motion + motion mode
        var smoothMotionToggle = ThemedToggle.create(colL, y, halfW, WIDGET_HEIGHT,
                "Smooth Motion", config.smoothMotionEnabled, v -> {
            config.smoothMotionEnabled = v; config.save();
        });
        smoothMotionToggle.setTooltip(tip("Adds frame blending / motion blur to recordings so fast movement looks smoother and more cinematic. Costs a little extra processing while recording."));
        addRenderableWidget(smoothMotionToggle);
        var motionModeButton = CycleButton.create(colR, y, halfW, WIDGET_HEIGHT,
                Component.literal("Motion: " + SmoothMotion.describe(config.smoothMotionMode)),
                b -> {
            config.smoothMotionMode = nextMotionMode(config.smoothMotionMode);
            config.save();
            b.setMessage(Component.literal("Motion: " + SmoothMotion.describe(config.smoothMotionMode)));
        }, b -> {
            config.smoothMotionMode = nextMotionMode(config.smoothMotionMode);
            config.save();
            b.setMessage(Component.literal("Motion: " + SmoothMotion.describe(config.smoothMotionMode)));
        });
        motionModeButton.setTooltip(tip("Chooses how Smooth Motion is produced: Blend mixes nearby frames together, Motion estimates movement between frames. Left or right-click to switch."));
        addRenderableWidget(motionModeButton);
        y += ROW_SPACING;

        // Row 3: frame pooling + performance optimizer
        var framePoolingToggle = ThemedToggle.create(colL, y, halfW, WIDGET_HEIGHT,
                "Frame Pooling", config.frameBufferPoolingEnabled, v -> {
            config.frameBufferPoolingEnabled = v; config.save();
        });
        framePoolingToggle.setTooltip(tip("Reuses frame memory buffers instead of allocating new ones every frame. Reduces stutter and garbage-collection lag during long recordings."));
        addRenderableWidget(framePoolingToggle);
        var perfOptimizerToggle = ThemedToggle.create(colR, y, halfW, WIDGET_HEIGHT,
                "Perf Optimizer", config.perfOptimizerEnabled, v -> {
            config.perfOptimizerEnabled = v; config.save();
        });
        perfOptimizerToggle.setTooltip(tip("Automatically lowers recording quality on the fly when your game FPS drops too low, so gameplay stays smooth. Works together with the Min FPS target."));
        addRenderableWidget(perfOptimizerToggle);
        y += ROW_SPACING;

        // Row 4: auto adjust + perf stats overlay
        var autoAdjustToggle = ThemedToggle.create(colL, y, halfW, WIDGET_HEIGHT,
                "Auto Adjust", config.perfAutoAdjust, v -> {
            config.perfAutoAdjust = v; config.save();
        });
        autoAdjustToggle.setTooltip(tip("Lets the Performance Optimizer actually change settings by itself. When off, the optimizer only watches and warns but will not modify anything."));
        addRenderableWidget(autoAdjustToggle);
        var optimizerOverlayToggle = ThemedToggle.create(colR, y, halfW, WIDGET_HEIGHT,
                "Optimizer Overlay", config.perfShowStatsOverlay, v -> {
            config.perfShowStatsOverlay = v; config.save();
        });
        optimizerOverlayToggle.setTooltip(tip("Shows a small live diagnostic overlay with the optimizer's current decisions and FPS readings. Handy for tuning; it is only baked into recordings if Bake in Overlay is on."));
        addRenderableWidget(optimizerOverlayToggle);
        y += ROW_SPACING;

        // Row 5: hud perf stats + min fps cycle
        var perfStatsHudToggle = ThemedToggle.create(colL, y, halfW, WIDGET_HEIGHT,
                "Perf Stats HUD", config.showPerformanceStats, v -> {
            config.showPerformanceStats = v; config.save();
        });
        perfStatsHudToggle.setTooltip(tip("Shows an on-screen performance readout (FPS, frame time, dropped frames) while recording. This is separate from the main recording info overlay."));
        addRenderableWidget(perfStatsHudToggle);
        var minFpsButton = CycleButton.create(colR, y, halfW, WIDGET_HEIGHT,
                Component.literal("Min FPS: " + config.perfMinFps),
                b -> {
            config.perfMinFps = nextMinFps(config.perfMinFps);
            config.save();
            b.setMessage(Component.literal("Min FPS: " + config.perfMinFps));
        }, b -> {
            config.perfMinFps = prevMinFps(config.perfMinFps);
            config.save();
            b.setMessage(Component.literal("Min FPS: " + config.perfMinFps));
        });
        minFpsButton.setTooltip(tip("The target FPS the Performance Optimizer tries to protect. If your game FPS falls below this, the optimizer lowers recording quality to recover. Left or right-click to change."));
        addRenderableWidget(minFpsButton);
        y += ROW_SPACING;

        addRenderableWidget(ThemedButton.create((this.width - 120) / 2, this.panelBottom - 26, 120, WIDGET_HEIGHT,
                Component.literal("Done"), b -> onClose()));
    }

    private static net.minecraft.client.gui.components.Tooltip tip(String s) {
        return net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(s));
    }

    private static String nextPreset(String current) {
        String[] all = RecordableConfig.DEVICE_PRESETS;
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(current)) return all[(i + 1) % all.length];
        }
        return all.length > 0 ? all[0] : current;
    }

    private static String prevPreset(String current) {
        String[] all = RecordableConfig.DEVICE_PRESETS;
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(current)) return all[(i - 1 + all.length) % all.length];
        }
        return all.length > 0 ? all[0] : current;
    }

    private static int nextMinFps(int current) {
        for (int i = 0; i < MIN_FPS_VALUES.length; i++) {
            if (MIN_FPS_VALUES[i] == current) return MIN_FPS_VALUES[(i + 1) % MIN_FPS_VALUES.length];
        }
        return MIN_FPS_VALUES[0];
    }

    private static int prevMinFps(int current) {
        for (int i = 0; i < MIN_FPS_VALUES.length; i++) {
            if (MIN_FPS_VALUES[i] == current) return MIN_FPS_VALUES[(i - 1 + MIN_FPS_VALUES.length) % MIN_FPS_VALUES.length];
        }
        return MIN_FPS_VALUES[0];
    }

    private static String nextMotionMode(String mode) {
        return SmoothMotion.MODE_BLEND.equals(SmoothMotion.sanitizeMode(mode))
                ? SmoothMotion.MODE_MOTION : SmoothMotion.MODE_BLEND;
    }

    // --- Render (panel drawn before widgets so buttons stay bright) --------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.extractMenuBackground(context);
        Font tr = this.font;
        ThemedPanel.drawPanel(context, panelX, panelY, panelX + PANEL_W, panelBottom);
        context.centeredText(tr, this.title, this.width / 2, panelY + 12, 0xFFFFFFFF);
        context.centeredText(tr, Component.literal("All performance options live here."),
                this.width / 2, panelY + 34 + 4 * ROW_SPACING + WIDGET_HEIGHT + 14, 0xFFB0B0B0);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
