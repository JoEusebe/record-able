package dev.recordable.screen;

import dev.recordable.EasterEgg;
import dev.recordable.VersionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
    public static Button create(Screen hostScreen) {
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

    public static Button create(Screen hostScreen, int x, int y) {
        LogoButton button = new LogoButton(x, y, b -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        java.util.UUID playerUUID = null;
                        try {
                            if (client.getUser() != null) {
                                playerUUID = client.getUser().getProfileId();
                            }
                        } catch (Throwable ignored) {
                        }
                        if (EasterEgg.enabled(playerUUID)) {
                            client.setScreenAndShow(new ThankYouScreen(hostScreen));
                        } else {
                            client.setScreenAndShow(new VideoCollectionScreen(hostScreen));
                        }
                    }
                });
        button.setTooltip(Tooltip.create(Component.literal("Record-able Video Collection")));
        return button;
    }

    private static final class LogoButton extends Button {
        private LogoButton(int x, int y, OnPress onPress) {
            super(x, y, SIZE, SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
            this.extractDefaultSprite(context);
            int iconX = this.getX() + (this.getWidth() - ICON_SIZE) / 2;
            int iconY = this.getY() + (this.getHeight() - ICON_SIZE) / 2;
            context.blit(RenderPipelines.GUI_TEXTURED, ICON, iconX, iconY, 0.0F, 0.0F,
                    ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        }
    }
}
