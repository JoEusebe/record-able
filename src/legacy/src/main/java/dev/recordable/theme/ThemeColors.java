package dev.recordable.theme;

/**
 * Color palette for a UI theme. All colors are in ARGB format (0xAARRGGBB).
 */
public final class ThemeColors {
    // ── Panel / Background ──
    public final int panelBackground;
    public final int panelBackgroundAlt;
    public final int panelBorder;

    // ── Accent / Interactive ──
    public final int accent;
    public final int accentHover;
    public final int accentDim;

    // ── Text ──
    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;
    public final int textError;
    public final int textSuccess;

    // ── Headers / Sections ──
    public final int headerText;
    public final int headerUnderline;
    public final int sectionBackground;
    public final int sectionHover;

    // ── Buttons ──
    public final int buttonBackground;
    public final int buttonBackgroundHover;
    public final int buttonBorder;
    public final int buttonText;

    // ── Scrollbar ──
    public final int scrollTrack;
    public final int scrollThumb;

    // ── Special Effects ──
    public final int scanlineColor;
    public final int grainColor;
    public final int glitchColor;
    public final int vignetteColor;

    public ThemeColors(Builder builder) {
        this.panelBackground = builder.panelBackground;
        this.panelBackgroundAlt = builder.panelBackgroundAlt;
        this.panelBorder = builder.panelBorder;
        this.accent = builder.accent;
        this.accentHover = builder.accentHover;
        this.accentDim = builder.accentDim;
        this.textPrimary = builder.textPrimary;
        this.textSecondary = builder.textSecondary;
        this.textMuted = builder.textMuted;
        this.textError = builder.textError;
        this.textSuccess = builder.textSuccess;
        this.headerText = builder.headerText;
        this.headerUnderline = builder.headerUnderline;
        this.sectionBackground = builder.sectionBackground;
        this.sectionHover = builder.sectionHover;
        this.buttonBackground = builder.buttonBackground;
        this.buttonBackgroundHover = builder.buttonBackgroundHover;
        this.buttonBorder = builder.buttonBorder;
        this.buttonText = builder.buttonText;
        this.scrollTrack = builder.scrollTrack;
        this.scrollThumb = builder.scrollThumb;
        this.scanlineColor = builder.scanlineColor;
        this.grainColor = builder.grainColor;
        this.glitchColor = builder.glitchColor;
        this.vignetteColor = builder.vignetteColor;
    }

    // ── Preset factories ──

    public static ThemeColors classic() {
        return new Builder()
                .panelBackground(0xD0111620).panelBackgroundAlt(0xD0182230).panelBorder(0xFF3A495F)
                .accent(0xFFE45F50).accentHover(0xFFFF7A68).accentDim(0xFF8E3D34)
                .textPrimary(0xFFF4F8FF).textSecondary(0xFFD7E0EF).textMuted(0xFFA5B0C3)
                .textError(0xFFFF8888).textSuccess(0xFF88E6A0)
                .headerText(0xFFF6FAFF).headerUnderline(0xFFE45F50)
                .sectionBackground(0xFF1A2432).sectionHover(0xFF233145)
                .buttonBackground(0xFF263345).buttonBackgroundHover(0xFF34455D).buttonBorder(0xFF4C607A).buttonText(0xFFEAF1FF)
                .scrollTrack(0xFF212E40).scrollThumb(0xFFE45F50)
                .scanlineColor(0x00000000).grainColor(0x00000000).glitchColor(0x00000000).vignetteColor(0x00000000)
                .build();
    }

    public static ThemeColors vhs() {
        return new Builder()
                .panelBackground(0xE00C0F1C).panelBackgroundAlt(0xE0131730).panelBorder(0xFF2A3F63)
                .accent(0xFFD63B33).accentHover(0xFFFF5B4F).accentDim(0xFF8E2621)
                .textPrimary(0xFFE7EADF).textSecondary(0xFFC3C8BD).textMuted(0xFF8A9188)
                .textError(0xFFFF6D6D).textSuccess(0xFF66F2A0)
                .headerText(0xFFF0F3E8).headerUnderline(0xFFD63B33)
                .sectionBackground(0xFF121A2B).sectionHover(0xFF19263B)
                .buttonBackground(0xFF1A2438).buttonBackgroundHover(0xFF24324D).buttonBorder(0xFF335176).buttonText(0xFFDDE2D8)
                .scrollTrack(0xFF1A2438).scrollThumb(0xFFD63B33)
                .scanlineColor(0x18000000).grainColor(0x0CFFFFFF).glitchColor(0x15FF0000).vignetteColor(0x40000000)
                .build();
    }

    public static ThemeColors cinema() {
        return new Builder()
                .panelBackground(0xE018130B).panelBackgroundAlt(0xE0241C11).panelBorder(0xFF5D4629)
                .accent(0xFFE0B75A).accentHover(0xFFF5CC78).accentDim(0xFF9C7F3D)
                .textPrimary(0xFFF9EED8).textSecondary(0xFFD8C6A7).textMuted(0xFFA89068)
                .textError(0xFFFF8B68).textSuccess(0xFF95E077)
                .headerText(0xFFFDF2DD).headerUnderline(0xFFE0B75A)
                .sectionBackground(0xFF21170C).sectionHover(0xFF302113)
                .buttonBackground(0xFF2A1F13).buttonBackgroundHover(0xFF3A2B1A).buttonBorder(0xFF6B5330).buttonText(0xFFE8D6B4)
                .scrollTrack(0xFF2A1F13).scrollThumb(0xFFE0B75A)
                .scanlineColor(0x00000000).grainColor(0x0AFFDDAA).glitchColor(0x00000000).vignetteColor(0x50000000)
                .build();
    }

    public static ThemeColors neon() {
        return new Builder()
                .panelBackground(0xE0090820).panelBackgroundAlt(0xE0121134).panelBorder(0xFF5340B8)
                .accent(0xFFFF47D2).accentHover(0xFFFF82E7).accentDim(0xFFB7339B)
                .textPrimary(0xFFF8F5FF).textSecondary(0xFFDAD3F7).textMuted(0xFFAAA0D4)
                .textError(0xFFFF6C98).textSuccess(0xFF68FFD1)
                .headerText(0xFF79FFF3).headerUnderline(0xFFFF47D2)
                .sectionBackground(0xFF131030).sectionHover(0xFF201847)
                .buttonBackground(0xFF1C1747).buttonBackgroundHover(0xFF2B1F63).buttonBorder(0xFF6D57D9).buttonText(0xFFF2EEFF)
                .scrollTrack(0xFF1B1642).scrollThumb(0xFFFF47D2)
                .scanlineColor(0x08FF4BD2).grainColor(0x08FFFFFF).glitchColor(0x1200F0FF).vignetteColor(0x3003002E)
                .build();
    }

    public static ThemeColors minimal() {
        return new Builder()
                .panelBackground(0xE0151820).panelBackgroundAlt(0xE01B2029).panelBorder(0xFF3A4250)
                .accent(0xFFC7D4E8).accentHover(0xFFE3ECFA).accentDim(0xFF8F9DB3)
                .textPrimary(0xFFF1F4F8).textSecondary(0xFFCBD2DC).textMuted(0xFF9DA7B6)
                .textError(0xFFFF7C7C).textSuccess(0xFF7CE49D)
                .headerText(0xFFF8FBFF).headerUnderline(0xFF6E7D95)
                .sectionBackground(0xFF1F2430).sectionHover(0xFF2A3241)
                .buttonBackground(0xFF2A3140).buttonBackgroundHover(0xFF394356).buttonBorder(0xFF505E74).buttonText(0xFFE4EAF2)
                .scrollTrack(0xFF2A3140).scrollThumb(0xFFA4B2C8)
                .scanlineColor(0x00000000).grainColor(0x00000000).glitchColor(0x00000000).vignetteColor(0x00000000)
                .build();
    }

    public static ThemeColors galaxy() {
        return new Builder()
                .panelBackground(0xE0081216).panelBackgroundAlt(0xE0111C24).panelBorder(0xFF2F5866)
                .accent(0xFF3CC8B4).accentHover(0xFF83F2E0).accentDim(0xFF2A7D77)
                .textPrimary(0xFFE9FFF9).textSecondary(0xFFC1E7DD).textMuted(0xFF88B9B0)
                .textError(0xFFFF8A9E).textSuccess(0xFF9BFFC8)
                .headerText(0xFFB9FFF2).headerUnderline(0xFF3CC8B4)
                .sectionBackground(0xFF0F1A21).sectionHover(0xFF162831)
                .buttonBackground(0xFF16303A).buttonBackgroundHover(0xFF1F3F4D).buttonBorder(0xFF3A6D78).buttonText(0xFFDDFCF4)
                .scrollTrack(0xFF122A33).scrollThumb(0xFF3CC8B4)
                .scanlineColor(0x00000000).grainColor(0x00000000).glitchColor(0x00000000).vignetteColor(0x22000508)
                .build();
    }

    public static ThemeColors forPreset(ThemePreset preset) {
        return switch (preset) {
            case VHS -> vhs();
            case CINEMA -> cinema();
            case NEON -> neon();
            case GALAXY -> galaxy();
            case MINIMAL -> minimal();
            default -> classic();
        };
    }

    // ── Builder ──

    public static final class Builder {
        private int panelBackground = 0xD0111620;
        private int panelBackgroundAlt = 0xD0182230;
        private int panelBorder = 0xFF3A495F;
        private int accent = 0xFFE45F50;
        private int accentHover = 0xFFFF7A68;
        private int accentDim = 0xFF8E3D34;
        private int textPrimary = 0xFFF4F8FF;
        private int textSecondary = 0xFFD7E0EF;
        private int textMuted = 0xFFA5B0C3;
        private int textError = 0xFFFF8888;
        private int textSuccess = 0xFF88E6A0;
        private int headerText = 0xFFF6FAFF;
        private int headerUnderline = 0xFFE45F50;
        private int sectionBackground = 0xFF1A2432;
        private int sectionHover = 0xFF233145;
        private int buttonBackground = 0xFF263345;
        private int buttonBackgroundHover = 0xFF34455D;
        private int buttonBorder = 0xFF4C607A;
        private int buttonText = 0xFFEAF1FF;
        private int scrollTrack = 0xFF212E40;
        private int scrollThumb = 0xFFE45F50;
        private int scanlineColor = 0x00000000;
        private int grainColor = 0x00000000;
        private int glitchColor = 0x00000000;
        private int vignetteColor = 0x00000000;

        public Builder panelBackground(int v) { this.panelBackground = v; return this; }
        public Builder panelBackgroundAlt(int v) { this.panelBackgroundAlt = v; return this; }
        public Builder panelBorder(int v) { this.panelBorder = v; return this; }
        public Builder accent(int v) { this.accent = v; return this; }
        public Builder accentHover(int v) { this.accentHover = v; return this; }
        public Builder accentDim(int v) { this.accentDim = v; return this; }
        public Builder textPrimary(int v) { this.textPrimary = v; return this; }
        public Builder textSecondary(int v) { this.textSecondary = v; return this; }
        public Builder textMuted(int v) { this.textMuted = v; return this; }
        public Builder textError(int v) { this.textError = v; return this; }
        public Builder textSuccess(int v) { this.textSuccess = v; return this; }
        public Builder headerText(int v) { this.headerText = v; return this; }
        public Builder headerUnderline(int v) { this.headerUnderline = v; return this; }
        public Builder sectionBackground(int v) { this.sectionBackground = v; return this; }
        public Builder sectionHover(int v) { this.sectionHover = v; return this; }
        public Builder buttonBackground(int v) { this.buttonBackground = v; return this; }
        public Builder buttonBackgroundHover(int v) { this.buttonBackgroundHover = v; return this; }
        public Builder buttonBorder(int v) { this.buttonBorder = v; return this; }
        public Builder buttonText(int v) { this.buttonText = v; return this; }
        public Builder scrollTrack(int v) { this.scrollTrack = v; return this; }
        public Builder scrollThumb(int v) { this.scrollThumb = v; return this; }
        public Builder scanlineColor(int v) { this.scanlineColor = v; return this; }
        public Builder grainColor(int v) { this.grainColor = v; return this; }
        public Builder glitchColor(int v) { this.glitchColor = v; return this; }
        public Builder vignetteColor(int v) { this.vignetteColor = v; return this; }
        public ThemeColors build() { return new ThemeColors(this); }
    }
}
