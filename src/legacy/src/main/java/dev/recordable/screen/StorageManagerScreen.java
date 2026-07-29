package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.RecordableConfig;
import dev.recordable.StorageManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage Manager screen (V1-0.06 Feature 6).
 * Shows a disk usage dashboard, a list of recordings with protect/delete
 * quick-actions, and manual + automatic cleanup controls.
 */
public final class StorageManagerScreen extends Screen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int listTop;
    private int listBottom;
    private int scrollOffset;

    private List<StorageManager.StoredFile> files = new ArrayList<>();
    private StorageManager.StorageStats stats;
    private String statusMessage = "";

    public StorageManagerScreen(Screen parent) {
        super(Text.translatable("screen.recordable.storage.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.max(360, Math.min((int) (this.width * 0.85D), 640));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.05D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(320, (int) (this.height * 0.88D)));
        this.listTop = this.panelTop + 96;
        this.listBottom = this.panelBottom - 40;
        this.scrollOffset = 0;

        refreshData();
        rebuildWidgets();
    }

    private void refreshData() {
        RecordableConfig config = RecordableConfig.get();
        this.files = StorageManager.listRecordings(config);
        this.stats = StorageManager.computeStats(config);
    }

    private void rebuildWidgets() {
        this.clearChildren();
        RecordableConfig config = RecordableConfig.get();

        int btnW = 110;
        int btnH = 20;
        int topBtnY = this.panelTop + 50;

        // Clean Now (forced manual cleanup honoring age/size rules)
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.recordable.storage.clean_now"),
                button -> {
                    StorageManager.CleanupResult result = StorageManager.runCleanup(RecordableConfig.get(), true);
                    this.statusMessage = "Removed " + result.filesDeleted() + " file(s), freed " + result.bytesFreedDisplay();
                    refreshData();
                    rebuildWidgets();
                }).dimensions(this.panelLeft + 12, topBtnY, btnW, btnH).build());

        // Auto-cleanup toggle
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Auto-Cleanup: " + (config.autoCleanupEnabled ? "ON" : "OFF")),
                button -> {
                    RecordableConfig c = RecordableConfig.get();
                    c.autoCleanupEnabled = !c.autoCleanupEnabled;
                    c.save();
                    rebuildWidgets();
                }).dimensions(this.panelLeft + 12 + btnW + 8, topBtnY, btnW + 20, btnH).build());

        // Refresh
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.recordable.storage.refresh"),
                button -> {
                    refreshData();
                    rebuildWidgets();
                }).dimensions(this.panelLeft + this.panelWidth - btnW - 12, topBtnY, btnW, btnH).build());

        // Per-file protect/delete buttons
        int rightEdge = this.panelLeft + this.panelWidth - 12;
        for (int i = 0; i < files.size(); i++) {
            StorageManager.StoredFile f = files.get(i);
            int rowY = this.listTop + (i * ROW_HEIGHT) - this.scrollOffset;
            if (rowY < this.listTop - ROW_HEIGHT || rowY > this.listBottom) continue;

            ButtonWidget protectBtn = ButtonWidget.builder(
                    Text.literal(f.protectedFlag() ? "Unlock" : "Protect"),
                    button -> {
                        StorageManager.toggleProtected(RecordableConfig.get(), f.filename());
                        refreshData();
                        rebuildWidgets();
                    }).dimensions(rightEdge - 120, rowY, 56, 18).build();
            this.addDrawableChild(protectBtn);

            ButtonWidget deleteBtn = ButtonWidget.builder(
                    Text.literal("Delete"),
                    button -> {
                        if (!f.protectedFlag()) {
                            StorageManager.deleteRecording(RecordableConfig.get(), f.path());
                            this.statusMessage = "Deleted " + f.filename();
                            refreshData();
                            rebuildWidgets();
                        } else {
                            this.statusMessage = f.filename() + " is protected.";
                        }
                    }).dimensions(rightEdge - 60, rowY, 56, 18).build();
            deleteBtn.active = !f.protectedFlag();
            this.addDrawableChild(deleteBtn);
        }

        // Back
        int backW = 120;
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.recordable.storage.back"),
                button -> close()
        ).dimensions((this.width - backW) / 2, this.panelBottom - 28, backW, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = files.size() * ROW_HEIGHT;
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

        // Dashboard line
        int textLeft = this.panelLeft + 14;
        if (stats != null) {
            String diskLine = "Disk: " + stats.diskFreeDisplay() + " free / " + stats.diskTotalDisplay()
                    + " total  (" + stats.diskUsedPercent() + "% used)";
            int diskColor = stats.diskUsedPercent() >= 90 ? WARNING_COLOR : HIGHLIGHT_COLOR;
            RenderHelper.drawText(context, this.textRenderer, Text.literal(diskLine), textLeft, this.panelTop + 18, diskColor);

            String recLine = "Recordings: " + stats.recordingCount() + " file(s), " + stats.recordingsDisplay();
            RenderHelper.drawText(context, this.textRenderer, Text.literal(recLine), textLeft, this.panelTop + 32, TEXT_COLOR);
        }

        // Header for the list
        RenderHelper.drawText(context, this.textRenderer, Text.literal("§lRecordings"), textLeft, this.listTop - 14, HEADER_COLOR);

        // File rows
        context.enableScissor(this.panelLeft, this.listTop, this.panelLeft + this.panelWidth, this.listBottom);
        for (int i = 0; i < files.size(); i++) {
            StorageManager.StoredFile f = files.get(i);
            int rowY = this.listTop + (i * ROW_HEIGHT) - this.scrollOffset;
            if (rowY < this.listTop - ROW_HEIGHT || rowY > this.listBottom) continue;

            if (i % 2 == 0) {
                context.fill(this.panelLeft + 8, rowY - 2, this.panelLeft + this.panelWidth - 8, rowY + ROW_HEIGHT - 4, 0x30FFFFFF);
            }
            String name = f.filename();
            int maxChars = Math.max(10, (this.panelWidth - 280) / 6);
            if (name.length() > maxChars) name = name.substring(0, maxChars - 1) + "…";
            int nameColor = f.protectedFlag() ? HIGHLIGHT_COLOR : TEXT_COLOR;
            String prefix = f.protectedFlag() ? "🔒 " : "";
            RenderHelper.drawText(context, this.textRenderer, Text.literal(prefix + name), textLeft, rowY + 3, nameColor);
            RenderHelper.drawText(context, this.textRenderer, Text.literal(f.sizeDisplay()),
                    this.panelLeft + this.panelWidth - 200, rowY + 3, TEXT_COLOR);
        }
        context.disableScissor();

        if (files.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No recordings found."),
                    this.width / 2, this.listTop + 10, TEXT_COLOR);
        }

        if (!statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusMessage),
                    this.width / 2, this.panelBottom - 42, HIGHLIGHT_COLOR);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
