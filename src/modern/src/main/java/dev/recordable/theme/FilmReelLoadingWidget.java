package dev.recordable.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * An animated film reel loading indicator widget.
 * Shows two spinning "reels" connected by a film strip, with optional status text.
 */
public class FilmReelLoadingWidget extends AbstractWidget {
    private String statusText;
    private float progress = -1f; // -1 = indeterminate

    public FilmReelLoadingWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Loading..."));
        this.statusText = "Loading...";
    }

    public void setStatusText(String text) { this.statusText = text; }
    public void setProgress(float progress) { this.progress = progress; }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        ThemeColors colors = ThemeEngine.get().colors();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        // Background
        context.fill(x, y, x + w, y + h, colors.sectionBackground);

        // Two reel circles
        int reelRadius = Math.min(h / 3, 12);
        int leftReelX = x + w / 3;
        int rightReelX = x + 2 * w / 3;
        int reelY = y + h / 2 - 4;

        long tick = System.currentTimeMillis();
        drawSpinningReel(context, leftReelX, reelY, reelRadius, tick, colors);
        drawSpinningReel(context, rightReelX, reelY, reelRadius, tick + 500, colors);

        // Film strip connecting reels
        int stripY = reelY - 1;
        context.fill(leftReelX + reelRadius, stripY, rightReelX - reelRadius, stripY + 3, colors.accent & 0x80FFFFFF);
        // Sprocket dots on strip
        for (int sx = leftReelX + reelRadius + 2; sx < rightReelX - reelRadius - 2; sx += 6) {
            int dotOffset = (int) ((tick / 80) % 6);
            context.fill(sx + dotOffset, stripY, sx + dotOffset + 2, stripY + 1, colors.textMuted);
        }

        // Progress bar or indeterminate
        int barY = y + h - 6;
        if (progress >= 0) {
            VhsEffectsRenderer.renderTapeLoadingBar(context, x + 4, barY, x + w - 4, progress);
        } else {
            // Indeterminate shimmer
            int shimmerWidth = w / 4;
            int shimmerPos = (int) ((tick / 8) % (w + shimmerWidth)) - shimmerWidth;
            int shimmerLeft = Math.max(x + 4, x + shimmerPos);
            int shimmerRight = Math.min(x + w - 4, x + shimmerPos + shimmerWidth);
            ctx(context, x + 4, barY, x + w - 4, barY + 3, colors.panelBorder);
            if (shimmerRight > shimmerLeft) {
                ctx(context, shimmerLeft, barY, shimmerRight, barY + 3, colors.accent);
            }
        }

        // Status text
        if (statusText != null) {
            Minecraft mc = Minecraft.getInstance();
            context.centeredText(mc.font, statusText, x + w / 2, y + 3, colors.textSecondary);
        }
    }

    private void drawSpinningReel(GuiGraphicsExtractor context, int cx, int cy, int radius, long tick, ThemeColors colors) {
        // Outer circle (simple square approximation)
        context.fill(cx - radius, cy - radius, cx + radius, cy + radius, colors.panelBorder);
        context.fill(cx - radius + 1, cy - radius + 1, cx + radius - 1, cy + radius - 1, colors.sectionBackground);

        // Spinning spokes
        double angle = (tick % 1500) / 1500.0 * Math.PI * 2;
        for (int i = 0; i < 3; i++) {
            double a = angle + i * Math.PI * 2 / 3;
            int dx = (int) (Math.cos(a) * (radius - 2));
            int dy = (int) (Math.sin(a) * (radius - 2));
            context.fill(cx - 1, cy - 1, cx + dx, cy + dy, colors.accent);
        }

        // Center dot
        context.fill(cx - 2, cy - 2, cx + 2, cy + 2, colors.accent);
    }

    private static void ctx(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y2, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {}
}
