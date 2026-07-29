package dev.recordable;

import dev.recordable.compat.RenderHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Manages deferred (offline-render) capture sessions that were saved for later.
 *
 * <p>Lists every pending session from {@link DeferredCaptureManager#getPendingSessions()}
 * and offers per-session "Render Now" and "Delete" actions, a "Render All" batch action,
 * and a "Close" button. Shows a placeholder message when nothing is pending.
 */
public final class PendingRendersScreen extends Screen {

    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int SUBTEXT_COLOR = 0xFF9A9A9A;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int ROW_HEIGHT = 42;

    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final Screen parent;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int listTop;
    private int listBottom;
    private int scrollOffset;

    private List<DeferredCaptureManager.DeferredRecordingMetadata> sessions = new ArrayList<>();
    private String statusMessage = "";

    public PendingRendersScreen(Screen parent) {
        super(Text.literal("Pending Renders"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.max(360, Math.min((int) (this.width * 0.85D), 640));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.05D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(300, (int) (this.height * 0.88D)));
        this.listTop = this.panelTop + 70;
        this.listBottom = this.panelBottom - 40;
        this.scrollOffset = 0;

        refreshData();
        rebuildWidgets();
    }

    private void refreshData() {
        this.sessions = DeferredCaptureManager.getInstance().getPendingSessions();
    }

    private void rebuildWidgets() {
        this.clearChildren();

        int btnW = 110;
        int btnH = 20;
        int topBtnY = this.panelTop + 40;

        // Render All (batch)
        ButtonWidget renderAllBtn = ButtonWidget.builder(
                Text.literal("Render All"),
                button -> renderAll()
        ).dimensions(this.panelLeft + 12, topBtnY, btnW, btnH).build();
        renderAllBtn.active = !sessions.isEmpty();
        this.addDrawableChild(renderAllBtn);

        // Refresh
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Refresh"),
                button -> {
                    refreshData();
                    rebuildWidgets();
                }).dimensions(this.panelLeft + this.panelWidth - btnW - 12, topBtnY, btnW, btnH).build());

        // Per-session action buttons
        int rightEdge = this.panelLeft + this.panelWidth - 12;
        for (int i = 0; i < sessions.size(); i++) {
            DeferredCaptureManager.DeferredRecordingMetadata meta = sessions.get(i);
            final String sessionId = meta.sessionId;
            int rowY = this.listTop + (i * ROW_HEIGHT) - this.scrollOffset;
            if (rowY < this.listTop - ROW_HEIGHT || rowY > this.listBottom) continue;

            int btnRowY = rowY + 14;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Render Now"),
                    button -> renderSession(sessionId)
            ).dimensions(rightEdge - 150, btnRowY, 84, 18).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Delete"),
                    button -> deleteSession(sessionId)
            ).dimensions(rightEdge - 60, btnRowY, 56, 18).build());
        }

        // Close
        int closeW = 120;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Close"),
                button -> close()
        ).dimensions((this.width - closeW) / 2, this.panelBottom - 28, closeW, 20).build());
    }

    private void renderSession(String sessionId) {
        RecordableConfig cfg = RecordableConfig.get();
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                "§aStarting offline render... This will take 5-10x the recording duration.", false);
        OfflineRenderer.renderAsync(
                sessionId,
                cfg.getOutputDirectory(),
                cfg.deferredKeepTempFrames,
                progress -> { }
        ).whenComplete((outputPath, error) -> {
            if (error != null) {
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                        "§cOffline render failed: " + error.getMessage() + ". Check logs. Temp frames kept.", false);
            } else {
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                        "§aOffline render complete! Saved to: " + outputPath.getFileName(), false);
            }
        });
        this.statusMessage = "Render started. It will finish in the background.";
        refreshData();
        rebuildWidgets();
    }

    private void renderAll() {
        if (sessions.isEmpty()) {
            return;
        }
        RecordableConfig cfg = RecordableConfig.get();
        int count = sessions.size();
        for (DeferredCaptureManager.DeferredRecordingMetadata meta : new ArrayList<>(sessions)) {
            final String sessionId = meta.sessionId;
            OfflineRenderer.renderAsync(
                    sessionId,
                    cfg.getOutputDirectory(),
                    cfg.deferredKeepTempFrames,
                    progress -> { }
            ).whenComplete((outputPath, error) -> {
                if (error != null) {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                            "§cOffline render failed: " + error.getMessage() + ". Check logs.", false);
                } else {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                            "§aOffline render complete! Saved to: " + outputPath.getFileName(), false);
                }
            });
        }
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                "§aStarted rendering " + count + " pending session(s) in the background.", false);
        this.statusMessage = "Rendering " + count + " session(s) in the background.";
        refreshData();
        rebuildWidgets();
    }

    private void deleteSession(String sessionId) {
        DeferredCaptureManager.getInstance().deleteSession(sessionId);
        this.statusMessage = "Deleted pending session and its temp frames.";
        refreshData();
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = sessions.size() * ROW_HEIGHT;
        int viewHeight = this.listBottom - this.listTop;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int delta = (int) Math.round(verticalAmount * -ROW_HEIGHT);
        if (delta == 0) delta = verticalAmount > 0 ? -ROW_HEIGHT : ROW_HEIGHT;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int accent = 0xFF000000 | RecordableConfig.get().getMenuAccentColorRgb();
        int left = this.panelLeft - 6;
        int right = this.panelLeft + this.panelWidth + 6;
        context.fill(left, this.panelTop - 6, right, this.panelBottom, PANEL_COLOR);
        context.fill(left, this.panelTop - 6, right, this.panelTop - 5, accent);
        context.fill(left, this.panelBottom - 1, right, this.panelBottom, PANEL_BORDER_COLOR);
        context.fill(left, this.panelTop - 6, left + 1, this.panelBottom, PANEL_BORDER_COLOR);
        context.fill(right - 1, this.panelTop - 6, right, this.panelBottom, PANEL_BORDER_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.panelTop, 0xFFFFFFFF);

        int textLeft = this.panelLeft + 14;
        RenderHelper.drawText(context, this.textRenderer,
                Text.literal(sessions.size() + " pending session(s)"),
                textLeft, this.panelTop + 22, TEXT_COLOR);

        if (sessions.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No pending renders."),
                    this.width / 2, this.listTop + 20, TEXT_COLOR);
        } else {
            DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
            context.enableScissor(this.panelLeft, this.listTop, this.panelLeft + this.panelWidth, this.listBottom);
            for (int i = 0; i < sessions.size(); i++) {
                DeferredCaptureManager.DeferredRecordingMetadata meta = sessions.get(i);
                int rowY = this.listTop + (i * ROW_HEIGHT) - this.scrollOffset;
                if (rowY < this.listTop - ROW_HEIGHT || rowY > this.listBottom) continue;

                if (i % 2 == 0) {
                    context.fill(this.panelLeft + 8, rowY - 2, this.panelLeft + this.panelWidth - 8,
                            rowY + ROW_HEIGHT - 6, 0x30FFFFFF);
                }

                String title = meta.originalName != null ? meta.originalName : meta.sessionId;
                int maxChars = Math.max(10, (this.panelWidth - 200) / 6);
                if (title.length() > maxChars) title = title.substring(0, maxChars - 1) + "...";
                RenderHelper.drawText(context, this.textRenderer, Text.literal(title),
                        textLeft, rowY + 2, HEADER_COLOR);

                long sizeBytes = dcm.calculateSessionSize(meta.sessionId);
                String detail = TIMESTAMP_FORMAT.format(new Date(meta.startTimeMs))
                        + "  |  " + meta.captureFps + " -> " + meta.targetFps + " fps"
                        + "  |  " + meta.totalFrames + " frames"
                        + "  |  " + formatSize(sizeBytes);
                RenderHelper.drawText(context, this.textRenderer, Text.literal(detail),
                        textLeft, rowY + 15, SUBTEXT_COLOR);
            }
            context.disableScissor();
        }

        if (!statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusMessage),
                    this.width / 2, this.panelBottom - 42, HIGHLIGHT_COLOR);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
