package dev.recordable;

import dev.recordable.theme.ThemedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private RenderPromptScreen(Screen parent, String sessionId, DeferredCaptureManager.DeferredRecordingMetadata metadata) {
        super(Component.literal("Render recording?"));
        this.parent = parent;
        this.sessionId = sessionId;
        this.metadata = metadata;
    }

    /** Opens the render prompt for the given session on the client thread. */
    public static void openFor(Minecraft client, String sessionId) {
        if (client == null || sessionId == null) {
            return;
        }
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        DeferredCaptureManager.DeferredRecordingMetadata meta = dcm.getMetadata(sessionId);
        if (meta == null) {
            RecordableMod.LOGGER.warn("[RenderPrompt] Session {} not found.", sessionId);
            return;
        }
        Screen parent = VersionHelper.currentScreen(client);
        client.setScreenAndShow(new RenderPromptScreen(parent, sessionId, meta));
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

        this.addRenderableWidget(ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                Component.literal("Render Now"), button -> renderNow()));
        this.addRenderableWidget(ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                Component.literal("Render Later"), button -> renderLater()));
        this.addRenderableWidget(ThemedButton.create(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, buttonHeight,
                Component.literal("Cancel"), button -> cancel()));
    }

    private void renderNow() {
        RecordableConfig cfg = RecordableConfig.get();
        
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
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
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
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
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft, msg, false);
                } else {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
                            "§aOffline render complete! Saved to: " + outputPath.getFileName(), false);
                }
            }
        });
        
        closeToParent();
    }

    private void renderLater() {
        // Session stays in "pending" status, user can render it later from PendingRendersScreen
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
                "§eRecording saved. Render it later from settings > Pending Renders.", false);
        closeToParent();
    }

    private void cancel() {
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        dcm.deleteSession(sessionId);
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
                "§cDeferred recording cancelled and temp frames deleted.", false);
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Draw the darkened menu background and our panel FIRST, then let the
        // buttons render on top via super so they clearly stand out above the panel.
        this.extractMenuBackground(context);

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
        context.centeredText(this.font, Component.literal("Recording captured!"),
                this.width / 2, this.panelY + 14, TITLE_COLOR);

        // Session info
        int textX = this.panelX + 20;
        int textY = this.panelY + 40;
        int lineHeight = 12;

        if (metadata != null) {
            context.text(this.font, Component.literal("Captured at: " + metadata.captureFps + " fps"),
                    textX, textY, TEXT_COLOR);
            context.text(this.font, Component.literal("Will render to: " + metadata.targetFps + " fps"),
                    textX, textY + lineHeight, TEXT_COLOR);
            context.text(this.font, Component.literal("Interpolation: " + metadata.interpolation),
                    textX, textY + lineHeight * 2, TEXT_COLOR);
            
            // Calculate disk size
            DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
            long sizeBytes = dcm.calculateSessionSize(sessionId);
            String sizeStr = formatSize(sizeBytes);
            context.text(this.font, Component.literal("Temp frames: " + sizeStr),
                    textX, textY + lineHeight * 3, TEXT_COLOR);
        }

        // Instructions
        context.text(this.font, Component.literal("§7Render now (5-10x recording time) or save for later?"),
                textX, textY + lineHeight * 5, TEXT_COLOR);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void onClose() {
        // Default close action (ESC key) is same as "Render Later"
        renderLater();
    }
}
