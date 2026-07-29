package dev.recordable.theme;

import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;

/**
 * Central theme engine that provides the current theme colors and effects
 * to all UI components. Thread-safe singleton.
 *
 * <p>Usage: {@code ThemeEngine.get().colors()} from any render method.</p>
 */
public final class ThemeEngine {
    private static final ThemeEngine INSTANCE = new ThemeEngine();

    private volatile ThemePreset activePreset = ThemePreset.VHS;
    private volatile ThemeColors colors = ThemeColors.vhs();
    private volatile boolean scanlineEnabled = true;
    private volatile boolean grainEnabled = true;
    private volatile boolean glitchEnabled = true;
    private volatile boolean vignetteEnabled = true;
    private volatile boolean animationsEnabled = true;

    private ThemeEngine() {}

    public static ThemeEngine get() { return INSTANCE; }

    /** Reload theme from config. Call once at init and whenever config changes. */
    public void loadFromConfig() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null) return;
            ThemePreset preset = config.uiTheme;
            if (preset == null) preset = ThemePreset.VHS;
            applyPreset(preset);
            this.scanlineEnabled = config.uiScanlines;
            this.grainEnabled = config.uiFilmGrain;
            this.glitchEnabled = config.uiGlitchEffects;
            this.vignetteEnabled = config.uiVignette;
            this.animationsEnabled = config.uiAnimations;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("Failed to load theme from config", e);
        }
    }

    public void applyPreset(ThemePreset preset) {
        this.activePreset = preset;
        this.colors = ThemeColors.forPreset(preset);
    }

    public ThemePreset preset() { return activePreset; }
    public ThemeColors colors() { return colors; }
    public boolean scanlineEnabled() { return scanlineEnabled && (colors.scanlineColor & 0xFF000000) != 0; }
    public boolean grainEnabled() { return grainEnabled && (colors.grainColor & 0xFF000000) != 0; }
    public boolean glitchEnabled() { return glitchEnabled && (colors.glitchColor & 0xFF000000) != 0; }
    public boolean vignetteEnabled() { return vignetteEnabled && (colors.vignetteColor & 0xFF000000) != 0; }
    public boolean animationsEnabled() { return animationsEnabled; }

    /** Helper: lerp between two ARGB colors. t ∈ [0,1]. */
    public static int lerpColor(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aA = (a >> 24) & 0xFF, aR = (a >> 16) & 0xFF, aG = (a >> 8) & 0xFF, aB = a & 0xFF;
        int bA = (b >> 24) & 0xFF, bR = (b >> 16) & 0xFF, bG = (b >> 8) & 0xFF, bB = b & 0xFF;
        return ((int)(aA + (bA - aA) * t) << 24)
             | ((int)(aR + (bR - aR) * t) << 16)
             | ((int)(aG + (bG - aG) * t) << 8)
             | (int)(aB + (bB - aB) * t);
    }

    /** Pulse alpha for blinking effects. Returns 0.0-1.0. */
    public static float pulse(long tickMs, int periodMs) {
        float phase = (tickMs % periodMs) / (float) periodMs;
        return (float)(0.5 + 0.5 * Math.sin(phase * Math.PI * 2));
    }
}
