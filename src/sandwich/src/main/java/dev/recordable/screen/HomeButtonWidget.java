package dev.recordable.screen;

import dev.recordable.EasterEgg;
import dev.recordable.VersionHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Small title-screen icon button that opens the Video Collection.
 * Positioned to the right of the main menu buttons, matching the size
 * and style of other vanilla icon buttons (accessibility, language).
 */
public final class HomeButtonWidget {
    /** Match vanilla icon button size (20x20). */
    private static final int SIZE = 20;
    private static final int ICON_SIZE = 16;
    private static final Identifier ICON = VersionHelper.modId("icon.png");

    private HomeButtonWidget() {
    }

    /**
     * Creates a small mod-logo button placed beside the Multiplayer row.
     */
    public static ButtonWidget create(Screen hostScreen) {
        int hostWidth = hostScreen == null ? 0 : hostScreen.width;
        int hostHeight = hostScreen == null ? 0 : hostScreen.height;

        // Position: left of center buttons area, vertically aligned with Multiplayer button.
        // Vanilla title screen buttons are centered and 200px wide.
        int centerX = hostWidth / 2;
        int buttonAreaLeft = centerX - 100;
        int x = buttonAreaLeft - SIZE - 4;

        // Multiplayer row position in vanilla title screen.
        int y = hostHeight / 4 + 48 + 24;
        x = Math.max(0, x);
        y = Math.max(0, y);
        return create(hostScreen, x, y);
    }

    public static ButtonWidget create(Screen hostScreen, int x, int y) {
        LogoButton button = new LogoButton(x, y, b -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null) {
                        java.util.UUID playerUUID = null;
                        try {
                            if (client.getSession() != null) {
                                playerUUID = client.getSession().getUuidOrNull();
                            }
                        } catch (Throwable ignored) {
                        }
                        if (EasterEgg.enabled(playerUUID)) {
                            client.setScreen(new ThankYouScreen(hostScreen));
                        } else {
                            client.setScreen(new VideoCollectionScreen(hostScreen));
                        }
                    }
                });
        button.setTooltip(Tooltip.of(Text.translatable("screen.recordable.video_collection.title")));
        return button;
    }

    private static final class LogoButton extends ButtonWidget {
        private LogoButton(int x, int y, PressAction onPress) {
            super(x, y, SIZE, SIZE, net.minecraft.text.Text.translatable("screen.recordable.video_collection.title"), onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
            this.drawButton(context);
            int iconX = this.getX() + (this.getWidth() - ICON_SIZE) / 2;
            int iconY = this.getY() + (this.getHeight() - ICON_SIZE) / 2;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, ICON, iconX, iconY, 0.0F, 0.0F,
                    ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        }
    }
}
