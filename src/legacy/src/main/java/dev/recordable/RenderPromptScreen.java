package dev.recordable;

import dev.recordable.theme.ThemedButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Prompt shown after a deferred (offline-render) recording stops.
 * 
 * <p>Gives the user three choices:
 * <ul>
 *   <li><b>Render Now</b> - starts the offline renderer immediately</li>
 *   <li><b>Render Later</b> - keeps the session pending in the queue</li>
 *   <li><b>Cancel</b> - deletes the session and all temp frames</li>
 * </ul>
 */
public final class RenderPromptScreen extends Screen {

    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 200;
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF444444;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;

    private final Screen parent;
    private final String sessionId;
    private final DeferredCaptureManager.DeferredRecordingMetadata metadata;

    private int panelX;
    private int panelY;

    private ButtonWidget renderNowButton;
    private ButtonWidget renderLaterButton;
    private ButtonWidget cancelButton;

    private RenderPromptScreen(Screen parent, String sessionId, DeferredCaptureManager.DeferredRecordingMetadata metadata) {
        super(Text.literal("Render recording?"));
        this.parent = parent;
        this.sessionId = sessionId;
        this.metadata = metadata;
    }

    /** Opens the render prompt for the given session on the client thread. */
    public static void openFor(MinecraftClient client, String sessionId) {
        if (client == null || sessionId == null) {
            return;
        }
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        DeferredCaptureManager.DeferredRecordingMetadata meta = dcm.getMetadata(sessionId);
        if (meta == null) {
            RecordableMod.LOGGER.warn("[RenderPrompt] Session {} not found.", sessionId);
            return;
        }
        Screen parent = client.currentScreen;
        client.setScreen(new RenderPromptScreen(parent, sessionId, meta));
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        int buttonWidth = 110;
        int buttonHeight = 20;
        int gap = 10;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.panelY + PANEL_HEIGHT - buttonHeight - 16;

        this.renderNowButton = ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                Text.literal("Render Now"), button -> renderNow());
        this.addDrawableChild(this.renderNowButton);
        this.renderLaterButton = ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                Text.literal("Render Later"), button -> renderLater());
        this.addDrawableChild(this.renderLaterButton);
        this.cancelButton = ThemedButton.create(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, buttonHeight,
                Text.literal("Cancel"), button -> cancel());
        this.addDrawableChild(this.cancelButton);
    }

    private void renderNow() {
        RecordableConfig cfg = RecordableConfig.get();
        
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                "§aStarting offline render... This will take 5-10x the recording duration.", false);
        
        OfflineRenderer.renderAsync(
            sessionId,
            cfg.getOutputDirectory(),
            cfg.deferredKeepTempFrames,
            progress -> {
                // Progress callback (could update UI later)
            }
        ).whenComplete((outputPath, error) -> {
            if (error != null) {
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                        "§cOffline render failed: " + error.getMessage() + ". Check logs. Temp frames kept.", false);
            } else {
                // Video is fully rendered and ready now: confirm the save and arm the rename
                // prompt so the user can name the finished file (matches the deferred-render flow).
                RecordableConfig cfgAfter = RecordableConfig.get();
                RecordingManager rm = RecordingManager.getInstance();
                if (cfgAfter != null && cfgAfter.promptRenameAfterRecording) {
                    rm.requestPendingRename(outputPath, rm.getRenamePromptWindowMs());
                    String key = rm.getRenameKeyDisplayOrNull();
                    String msg = key == null
                            ? "§aRender complete: " + outputPath.getFileName()
                                    + " saved! Bind a \"Name recording\" key to rename it."
                            : "§aRender complete: " + outputPath.getFileName()
                                    + " saved! Press " + key + " within 15s to name it.";
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client, msg, false);
                } else {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                            "§aOffline render complete! Saved to: " + outputPath.getFileName(), false);
                }
            }
        });
        
        closeToParent();
    }

    private void renderLater() {
        // Session stays in "pending" status, user can render it later from PendingRendersScreen
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                "§eRecording saved. Render it later from settings > Pending Renders.", false);
        closeToParent();
    }

    private void cancel() {
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        dcm.deleteSession(sessionId);
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                "§cDeferred recording cancelled and temp frames deleted.", false);
        closeToParent();
    }

    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Draw panel with border
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, PANEL_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, BORDER_COLOR);
        context.fill(this.panelX, this.panelY + PANEL_HEIGHT - 1, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX + PANEL_WIDTH - 1, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Recording captured!"),
                this.width / 2, this.panelY + 14, TITLE_COLOR);

        // Session info
        int textX = this.panelX + 20;
        int textY = this.panelY + 40;
        int lineHeight = 12;

        if (metadata != null) {
            context.drawText(this.textRenderer, Text.literal("Captured at: " + metadata.captureFps + " fps"),
                    textX, textY, TEXT_COLOR, false);
            context.drawText(this.textRenderer, Text.literal("Will render to: " + metadata.targetFps + " fps"),
                    textX, textY + lineHeight, TEXT_COLOR, false);
            context.drawText(this.textRenderer, Text.literal("Interpolation: " + metadata.interpolation),
                    textX, textY + lineHeight * 2, TEXT_COLOR, false);
            
            // Calculate disk size
            DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
            long sizeBytes = dcm.calculateSessionSize(sessionId);
            String sizeStr = formatSize(sizeBytes);
            context.drawText(this.textRenderer, Text.literal("Temp frames: " + sizeStr),
                    textX, textY + lineHeight * 3, TEXT_COLOR, false);
        }

        // Instructions
        context.drawText(this.textRenderer, Text.literal("§7Render now (5-10x recording time) or save for later?"),
                textX, textY + lineHeight * 5, TEXT_COLOR, false);

        // Re-draw the buttons on top so the panel fill does not dim them.
        if (this.renderNowButton != null) {
            this.renderNowButton.render(context, mouseX, mouseY, delta);
        }
        if (this.renderLaterButton != null) {
            this.renderLaterButton.render(context, mouseX, mouseY, delta);
        }
        if (this.cancelButton != null) {
            this.cancelButton.render(context, mouseX, mouseY, delta);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void close() {
        // Default close action (ESC key) is same as "Render Later"
        renderLater();
    }
}
