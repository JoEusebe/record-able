package dev.recordable.theme;

/**
 * Available UI theme presets for the Record-able mod.
 * Each preset defines a complete visual identity including colors, effects, and layout style.
 */
public enum ThemePreset {
    CLASSIC("Classic", "Clean modern dark theme"),
    VHS("VHS Retro", "Nostalgic VHS tape aesthetic with scanlines and static"),
    CINEMA("Cinema", "Film strip and movie theater inspired"),
    NEON("Neon Synthwave", "Vibrant neon colors on dark background"),
    GALAXY("Galaxy Sculk", "Deep sculk-inspired ultraviolet with soft starlight glow"),
    MINIMAL("Minimal", "Ultra-clean minimal design");

    public final String displayName;
    public final String description;

    ThemePreset(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public ThemePreset next() {
        ThemePreset[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public ThemePreset prev() {
        ThemePreset[] values = values();
        return values[(this.ordinal() - 1 + values.length) % values.length];
    }

    public ThemeToggle[] toggles() {
        return switch (this) {
            case VHS -> new ThemeToggle[] {
                    ThemeToggle.SCANLINES, ThemeToggle.FILM_GRAIN, ThemeToggle.GLITCH_EFFECTS,
                    ThemeToggle.VIGNETTE, ThemeToggle.ANIMATIONS
            };
            case CINEMA -> new ThemeToggle[] {
                    ThemeToggle.FILM_GRAIN, ThemeToggle.VIGNETTE, ThemeToggle.ANIMATIONS
            };
            case NEON -> new ThemeToggle[] {
                    ThemeToggle.SCANLINES, ThemeToggle.FILM_GRAIN, ThemeToggle.GLITCH_EFFECTS,
                    ThemeToggle.VIGNETTE, ThemeToggle.ANIMATIONS
            };
            case GALAXY -> new ThemeToggle[] {
                    ThemeToggle.VIGNETTE, ThemeToggle.ANIMATIONS
            };
            case MINIMAL -> new ThemeToggle[] {
                    ThemeToggle.ANIMATIONS
            };
            case CLASSIC -> new ThemeToggle[] {
                    ThemeToggle.VIGNETTE, ThemeToggle.ANIMATIONS
            };
        };
    }

    public enum ThemeToggle {
        SCANLINES("Scanlines"),
        FILM_GRAIN("Film Grain"),
        GLITCH_EFFECTS("Glitch Effects"),
        VIGNETTE("Vignette"),
        ANIMATIONS("Animations");

        public final String label;

        ThemeToggle(String label) {
            this.label = label;
        }
    }
}
