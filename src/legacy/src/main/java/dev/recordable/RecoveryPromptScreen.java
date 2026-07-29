package dev.recordable;

import dev.recordable.theme.ThemedButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * Crash-recovery prompt.
 *
 * <p>Shown once on the title screen when a previous session ended without finalizing a
 * recording (a crash or a hard kill left the file unclosed). The player is asked whether
 * they want to recover the leftover recording. "Yes" best-effort remuxes the partial file
 * into a clean, playable container (falling back to preserving the raw data if FFmpeg is
 * unavailable); "No" discards it. Styled to match {@link RenameRecordingScreen}.</p>
 */
public final class RecoveryPromptScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 140;
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF444444;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;

    private final Screen parent;
    private final String recordingName;

    private ButtonWidget yesButton;
    private ButtonWidget noButton;
    private int panelX;
    private int panelY;

    public RecoveryPromptScreen(Screen parent, String recordingName) {
        super(Text.literal("Recover recording"));
        this.parent = parent;
        this.recordingName = recordingName == null ? "recording" : recordingName;
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        int buttonWidth = 150;
        int buttonHeight = 20;
        int gap = 10;
        int totalWidth = buttonWidth * 2 + gap;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.panelY + PANEL_HEIGHT - buttonHeight - 16;

        this.yesButton = ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                Text.literal("Yes"), button -> recover());
        this.addDrawableChild(this.yesButton);
        this.noButton = ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                Text.literal("No"), button -> discard());
        this.addDrawableChild(this.noButton);
    }

    private void recover() {
        String executable = null;
        try {
            FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
            if (status != null && status.found()) {
                executable = status.executable();
            }
        } catch (Throwable ignored) {
        }
        Path recovered = RecoveryManager.recoverPending(executable);
        if (recovered != null) {
            ToastQueue.push("Recovered recording saved.");
        } else {
            ToastQueue.push("Could not recover the recording.");
        }
        closeToParent();
    }

    private void discard() {
        RecoveryManager.discardPending();
        ToastQueue.push("Discarded the unfinished recording.");
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

        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, PANEL_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, BORDER_COLOR);
        context.fill(this.panelX, this.panelY + PANEL_HEIGHT - 1, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX + PANEL_WIDTH - 1, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Recover recording"),
                this.width / 2, this.panelY + 16, TITLE_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Do you wanna recover your recording?"),
                this.width / 2, this.panelY + 44, TEXT_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\"" + this.recordingName + "\""),
                this.width / 2, this.panelY + 60, TITLE_COLOR);

        if (this.yesButton != null) {
            this.yesButton.render(context, mouseX, mouseY, delta);
        }
        if (this.noButton != null) {
            this.noButton.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public void close() {
        // Escape keeps the recording pending so the prompt can be shown again next launch.
        closeToParent();
    }
}
