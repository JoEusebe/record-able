package dev.recordable.screen;

import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.theme.ThemedButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run welcome screen that appears when FFmpeg is not detected.
 *
 * <p>This screen prevents users from recording black videos by explaining what
 * FFmpeg is, why it is needed, and providing a direct download button. It appears
 * automatically on game start when FFmpeg is missing and the user has not seen it
 * before. After the user clicks "Download" or "Dismiss", the flag is set and the
 * screen will not appear again automatically.</p>
 */
public final class FfmpegWelcomeScreen extends Screen {

    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF444444;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int HIGHLIGHT_COLOR = 0xFF88FF88;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelHeight;

    public FfmpegWelcomeScreen(Screen parent) {
        super(Text.literal("FFmpeg Setup Required"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelHeight = 240;
        this.panelY = (this.height - this.panelHeight) / 2;

        int buttonWidth = 140;
        int buttonHeight = 20;
        int gap = 10;
        int buttonY = this.panelY + this.panelHeight - buttonHeight - 16;

        boolean autoSupported = FfmpegBundleManager.isAutoDownloadSupported();

        if (autoSupported) {
            // Show both "Download FFmpeg" and "Dismiss" buttons
            int totalWidth = buttonWidth * 2 + gap;
            int startX = (this.width - totalWidth) / 2;

            addDrawableChild(ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                    Text.literal("Download FFmpeg"), button -> {
                RecordableConfig.get().ffmpegFirstRunShown = true;
                RecordableConfig.get().save();
                if (this.client != null) {
                    this.client.setScreen(new FfmpegDownloadScreen(this.parent));
                }
            }));

            addDrawableChild(ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                    Text.literal("Dismiss"), button -> dismiss()));
        } else {
            // Only show "Dismiss" button (auto-download not supported)
            int centerX = (this.width - buttonWidth) / 2;
            addDrawableChild(ThemedButton.create(centerX, buttonY, buttonWidth, buttonHeight,
                    Text.literal("Dismiss"), button -> dismiss()));
        }
    }

    private void dismiss() {
        RecordableConfig.get().ffmpegFirstRunShown = true;
        RecordableConfig.get().save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Draw panel background
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + this.panelHeight, PANEL_COLOR);

        // Draw border
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, BORDER_COLOR);
        context.fill(this.panelX, this.panelY + this.panelHeight - 1, this.panelX + PANEL_WIDTH,
                this.panelY + this.panelHeight, BORDER_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + this.panelHeight, BORDER_COLOR);
        context.fill(this.panelX + PANEL_WIDTH - 1, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + this.panelHeight, BORDER_COLOR);

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("FFmpeg Setup Required"),
                this.width / 2, this.panelY + 12, TITLE_COLOR);

        // Body text
        int textX = this.panelX + 20;
        int textY = this.panelY + 36;
        int lineHeight = 12;
        int wrapWidth = PANEL_WIDTH - 40;

        boolean autoSupported = FfmpegBundleManager.isAutoDownloadSupported();

        String[] lines;
        if (autoSupported) {
            lines = new String[]{
                    "Record-able requires FFmpeg to encode video and audio.",
                    "",
                    "FFmpeg was not found on your system. Without it, recordings",
                    "will be black and unusable.",
                    "",
                    "Click \"Download FFmpeg\" to automatically download and install",
                    "FFmpeg from a trusted source:",
                    FfmpegBundleManager.getDownloadSourceDescription(),
                    "",
                    "Download size: " + FfmpegBundleManager.getEstimatedDownloadSize(),
                    "",
                    "Or click \"Dismiss\" to set it up manually later."
            };
        } else {
            lines = new String[]{
                    "Record-able requires FFmpeg to encode video and audio.",
                    "",
                    "FFmpeg was not found on your system. Without it, recordings",
                    "will be black and unusable.",
                    "",
                    "Auto-download is not available for your platform.",
                    "",
                    "Manual installation instructions:",
                    FfmpegBundleManager.getManualInstallInstructions()
            };
        }

        // Vulkan renderer note: V1-0.10 now has a desktop window-capture fallback.
        if (PlatformUtils.isVulkanRendererLoaded()) {
            List<String> withWarning = new ArrayList<>();
            withWarning.add("Warning: a Vulkan renderer (e.g. VulkanMod) is installed.");
            withWarning.add("Record-able will switch to Vulkan fallback window capture mode.");
            withWarning.add("This mode may reduce FPS and works best in windowed or borderless mode.");
            withWarning.add("");
            for (String l : lines) withWarning.add(l);
            lines = withWarning.toArray(new String[0]);
        }

        for (String line : lines) {
            if (line.isEmpty()) {
                textY += lineHeight / 2;
                continue;
            }
            // Wrap text if needed
            List<String> wrapped = wrapText(line, wrapWidth);
            for (String wrappedLine : wrapped) {
                int color = wrappedLine.contains("trusted source") || wrappedLine.contains("Download size")
                        ? HIGHLIGHT_COLOR : TEXT_COLOR;
                context.drawText(this.textRenderer, Text.literal(wrappedLine), textX, textY, color, false);
                textY += lineHeight;
            }
        }
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            String testLine = line.length() == 0 ? word : line + " " + word;
            if (this.textRenderer.getWidth(testLine) <= maxWidth) {
                if (line.length() > 0) line.append(" ");
                line.append(word);
            } else {
                if (line.length() > 0) {
                    result.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    result.add(word);
                }
            }
        }
        if (line.length() > 0) {
            result.add(line.toString());
        }
        return result;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderInGameBackground(context);
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public void close() {
        dismiss();
    }
}
