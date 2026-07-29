package dev.recordable.screen;

import dev.recordable.EasterEgg;
import dev.recordable.RecordableConfig;
import dev.recordable.VersionHelper;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemePreset;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Hidden easter-egg "thank you" screen (legacy, MC 1.20.2-1.20.4).
 *
 * <p>Opened from the title-screen home button only while the hidden
 * {@code iskasaisora} config flag is enabled. On open it switches the UI theme
 * to Neon Synthwave, plays a note-block cover of the Pixar "Up" theme, and shows
 * a picture of Lilly the cat with the words "Thank you so much". Any click, key
 * press or close returns to the previous screen.</p>
 */
public final class ThankYouScreen extends Screen {

    private static final Identifier LILLY = VersionHelper.modId("textures/gui/lilly.png");
    private static final int LILLY_W = 180;
    private static final int LILLY_H = 240;

    private final Screen parent;
    private SoundInstance song;
    private long openedAt;
    private boolean seenCounted;
    private ThemePreset previousTheme = ThemePreset.VHS;

    // Responsive layout, recomputed every frame from the current screen size.
    private int cx;
    private int imgX, imgY, imgW, imgH;
    private int titleY, capY;
    private int closeBtnX, closeBtnY, closeBtnW, closeBtnH;
    private int goodbyeBtnX, goodbyeBtnY, goodbyeBtnW, goodbyeBtnH;

    /**
     * Recomputes a responsive, top-to-bottom stack (title, image, caption, Close
     * button, goodbye button) that always fits within the current screen bounds.
     * The Lilly image is capped at 40% of the screen height and keeps its 3:4
     * aspect ratio, so it scales down cleanly on small Android displays.
     */
    private void computeLayout(int lineHeight) {
        this.cx = this.width / 2;

        int maxImgH = (int) (this.height * 0.40f);
        this.imgH = Math.max(60, Math.min(LILLY_H, maxImgH));
        this.imgW = this.imgH * LILLY_W / LILLY_H;
        // Never let the frame exceed the screen width either.
        int maxImgW = this.width - 24;
        if (this.imgW > maxImgW) {
            this.imgW = Math.max(45, maxImgW);
            this.imgH = this.imgW * LILLY_H / LILLY_W;
        }
        this.imgX = this.cx - this.imgW / 2;

        int gapTop = 6, gapCap = 8, gap1 = 8, gap2 = 6;
        this.closeBtnH = lineHeight + 8;
        this.goodbyeBtnH = lineHeight + 8;

        int stackH = lineHeight + gapTop + this.imgH + gapCap + lineHeight
                + gap1 + this.closeBtnH + gap2 + this.goodbyeBtnH;
        int top = (this.height - stackH) / 2;
        if (top < 4) top = 4;

        this.titleY = top;
        this.imgY = this.titleY + lineHeight + gapTop;
        this.capY = this.imgY + this.imgH + gapCap;

        this.closeBtnW = Math.min(this.width - 20, 120);
        this.closeBtnX = this.cx - this.closeBtnW / 2;
        this.closeBtnY = this.capY + lineHeight + gap1;

        this.goodbyeBtnW = Math.min(this.width - 20, 230);
        this.goodbyeBtnX = this.cx - this.goodbyeBtnW / 2;
        this.goodbyeBtnY = this.closeBtnY + this.closeBtnH + gap2;
    }

    private boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public ThankYouScreen(Screen parent) {
        super(Text.literal("Thank you so much"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.openedAt = System.currentTimeMillis();

        // Count this view once so the guide arrows can retire after two opens.
        if (!this.seenCounted) {
            this.seenCounted = true;
            EasterEgg.markSeen();
        }

        // Use Neon Synthwave only while this screen is open.
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null) {
                this.previousTheme = config.uiTheme != null ? config.uiTheme : ThemePreset.VHS;
            }
            ThemeEngine.get().applyPreset(ThemePreset.NEON);
        } catch (Throwable ignored) {
        }

        // Play the note-block "Up" theme once.
        if (this.song == null && this.client != null) {
            try {
                SoundEvent event = SoundEvent.of(VersionHelper.modId("thank_you"));
                this.song = PositionedSoundInstance.master(event, 1.0f);
                this.client.getSoundManager().play(this.song);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderSynthwaveBackground(context);
        super.render(context, mouseX, mouseY, delta);

        ThemeColors tc;
        try {
            tc = ThemeEngine.get().colors();
        } catch (Throwable t) {
            tc = ThemeColors.neon();
        }

        int lineHeight = this.textRenderer.fontHeight;
        computeLayout(lineHeight);

        // Glowing frame behind Lilly (scaled to fit the screen).
        context.fill(imgX - 3, imgY - 3, imgX + imgW + 3, imgY + imgH + 3, tc.accent);
        context.fill(imgX - 1, imgY - 1, imgX + imgW + 1, imgY + imgH + 1, 0xFF000000);
        context.drawTexture(LILLY, imgX, imgY, imgW, imgH, 0.0F, 0.0F,
                LILLY_W, LILLY_H, LILLY_W, LILLY_H);

        // Title above the picture.
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Thank you so much"),
                cx, titleY, tc.headerText);

        // Caption below the picture.
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Lilly says hi"),
                cx, capY, tc.textSecondary);

        // Close button.
        boolean closeHover = inRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH);
        context.fill(closeBtnX - 1, closeBtnY - 1, closeBtnX + closeBtnW + 1, closeBtnY + closeBtnH + 1, tc.accent);
        context.fill(closeBtnX, closeBtnY, closeBtnX + closeBtnW, closeBtnY + closeBtnH,
                closeHover ? 0xFF3A0A4A : 0xFF1A0033);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Close"),
                cx, closeBtnY + (closeBtnH - lineHeight) / 2, tc.headerText);

        // Goodbye button to disable the easter egg.
        boolean byeHover = inRect(mouseX, mouseY, goodbyeBtnX, goodbyeBtnY, goodbyeBtnW, goodbyeBtnH);
        context.fill(goodbyeBtnX - 1, goodbyeBtnY - 1, goodbyeBtnX + goodbyeBtnW + 1, goodbyeBtnY + goodbyeBtnH + 1, 0xFFFF6B6B);
        context.fill(goodbyeBtnX, goodbyeBtnY, goodbyeBtnX + goodbyeBtnW, goodbyeBtnY + goodbyeBtnH,
                byeHover ? 0xFF3A0A4A : 0xFF1A0033);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Go to menu and say bye to Lilly"),
                cx, goodbyeBtnY + (goodbyeBtnH - lineHeight) / 2, 0xFFFF6B6B);
    }

    /** Paints a simple neon synthwave backdrop: dark purple sky with a glowing horizon grid. */
    private void renderSynthwaveBackground(DrawContext context) {
        // Sky gradient.
        context.fillGradient(0, 0, this.width, this.height, 0xFF1A0033, 0xFF33003A);
        int horizon = (int) (this.height * 0.62);
        // Sun glow.
        context.fillGradient(0, horizon - 40, this.width, horizon, 0x00FF00FF, 0x66FF00FF);
        // Horizon line.
        context.fill(0, horizon, this.width, horizon + 1, 0xFF00FFFF);
        // Perspective grid lines below the horizon.
        for (int i = 1; i <= 8; i++) {
            int y = horizon + i * i * 2;
            if (y >= this.height) break;
            int alpha = Math.max(0x22, 0xAA - i * 0x12);
            context.fill(0, y, this.width, y + 1, (alpha << 24) | 0x00FF00FF);
        }
    }

    private void closeEgg() {
        restorePreviousTheme();
        if (this.song != null && this.client != null) {
            try {
                this.client.getSoundManager().stop(this.song);
            } catch (Throwable ignored) {
            }
        }
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Ignore the very first frames so the click that opened us does not immediately close it.
        if (System.currentTimeMillis() - this.openedAt > 250) {
            computeLayout(this.textRenderer.fontHeight);

            if (inRect(mouseX, mouseY, goodbyeBtnX, goodbyeBtnY, goodbyeBtnW, goodbyeBtnH)) {
                // User clicked "goodbye" - disable the easter egg permanently.
                disableEasterEgg();
                return true;
            }

            // Close button or a click anywhere else both close the screen.
            closeEgg();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    /**
     * Permanently disables the easter egg (kill switch) and opens the mod menu.
     * Sets iskasaisoraOff = true, which overrides the config flag and every
     * fallback account, so the egg stays off until the config JSON is hand-edited
     * back to false. Then stops the music and opens the Record-able video menu.
     */
    private void disableEasterEgg() {
        restorePreviousTheme();
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null) {
                config.iskasaisora = false;
                config.iskasaisoraOff = true;
                config.save();
            }
        } catch (Throwable ignored) {
        }
        if (this.song != null && this.client != null) {
            try {
                this.client.getSoundManager().stop(this.song);
            } catch (Throwable ignored) {
            }
        }

        if (this.client != null) {
            // Send the user to the mod's menu, not the title screen.
            this.client.setScreen(new VideoCollectionScreen(this.parent));
            // Show a goodbye toast.
            try {
                dev.recordable.ToastQueue.push("Bye Lilly. Easter egg turned off (edit config to re-enable).");
            } catch (Throwable ignored) {
            }
        }
    }

    private void restorePreviousTheme() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null && this.previousTheme != null) {
                config.uiTheme = this.previousTheme;
                config.save();
            }
            ThemeEngine.get().applyPreset(this.previousTheme != null ? this.previousTheme : ThemePreset.VHS);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        closeEgg();
        return true;
    }

    @Override
    public void close() {
        closeEgg();
    }

    @Override
    public boolean shouldPause() {
        return true;
    }
}
