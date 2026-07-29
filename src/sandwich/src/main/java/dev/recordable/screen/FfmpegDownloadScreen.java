package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run / on-demand FFmpeg downloader UI.
 *
 * <p>This screen explains to the user what is about to happen (download size,
 * source, integrity check) and shows a live progress bar while the download is
 * running. On success the user is sent back to {@code parent}; on failure a
 * concrete error message and platform-specific manual install instructions
 * are shown.</p>
 *
 * <p>Compliance note: this screen is the consent moment for Modrinth's
 * "no bundled binaries" rule - nothing is downloaded until the user clicks the
 * "Download FFmpeg" button. On Android (where auto-download is not supported)
 * the screen only shows manual instructions.</p>
 */
public final class FfmpegDownloadScreen extends Screen implements FfmpegBundleManager.ProgressListener {

    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;
    private static final int ERROR_COLOR = 0xFFFF7777;
    private static final int PROGRESS_BG = 0xFF202020;
    private static final int PROGRESS_BORDER = 0xFF606060;

    private final Screen parent;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;

    private ButtonWidget downloadButton;
    private ButtonWidget cancelButton;
    private ButtonWidget openFolderButton;

    private final List<Line> lines = new ArrayList<>();
    private FfmpegBundleManager.DownloadProgress currentProgress = FfmpegBundleManager.DownloadProgress.IDLE;
    private boolean failureShown = false;

    /** Output of the most recent "Test FFmpeg" / "Paste path" action, shown in the body. */
    private String testReport = null;

    // --- Scrolling support ---
    private static final int LINE_HEIGHT = 12;
    private static final int SCROLLBAR_WIDTH = 6;
    private int panelBodyTop;
    private int panelBodyBottom;
    private int contentHeight;
    private int scrollOffset;
    // Footer layout (computed in init, stacked bottom-up): main button row,
    // diagnostics toolbar row, and the download progress bar above them.
    private int mainRowY;
    private int toolbarRowY;
    private int progressBarTop;
    private boolean draggingScrollbar = false;
    private boolean draggingBody = false;
    /** Wrap width in pixels for body text; recomputed from panel width each init(). */
    private int textWrapWidth = 400;

    public FfmpegDownloadScreen(Screen parent) {
        super(Text.literal("Record-able: FFmpeg Setup"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearChildren();

        this.panelWidth = Math.min(this.width - 16, Math.max(320, Math.min((int) (this.width * 0.9D), 640)));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.08D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(280, (int) (this.height * 0.84D)));

        // Wrap body text to the usable panel width (left text inset + right
        // scrollbar/padding). Recomputed here so it adapts to any resolution.
        this.textWrapWidth = Math.max(80, this.panelWidth - 28);

        rebuildLines();

        // Scrollable body region: below the title, above the fixed footer
        // (progress bar + action buttons live in the bottom ~72px).
        this.panelBodyTop = this.panelTop + 22;
        // Footer rows are stacked from the bottom up so they never overlap:
        // main action buttons -> secondary toolbar -> progress bar -> body.
        this.mainRowY = this.panelBottom - 28;
        this.toolbarRowY = this.mainRowY - 26;
        this.progressBarTop = this.toolbarRowY - 26;
        this.panelBodyBottom = this.progressBarTop - 14;
        this.contentHeight = this.lines.size() * LINE_HEIGHT;
        int maxScroll = Math.max(0, this.contentHeight - (this.panelBodyBottom - this.panelBodyTop));
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));

        int btnW = 160;
        int btnH = 20;
        int btnY = this.mainRowY;
        int centerX = this.panelLeft + this.panelWidth / 2;

        boolean autoSupported = FfmpegBundleManager.isAutoDownloadSupported();
        boolean isDownloading = FfmpegBundleManager.isDownloading();
        boolean alreadyAvailable = FfmpegBundleManager.isBundledFfmpegAvailable();
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();

        if (alreadyAvailable) {
            // Single "Close" button.
            this.cancelButton = ButtonWidget.builder(Text.literal("Close"),
                    btn -> close())
                    .dimensions(centerX - btnW / 2, btnY, btnW, btnH)
                    .build();
            this.addDrawableChild(this.cancelButton);
        } else if (autoSupported) {
            String dlLabel = isDownloading ? "Downloading…" : 
                "Download FFmpeg (" + FfmpegBundleManager.getEstimatedDownloadSize() + ")"; 
            this.downloadButton = ButtonWidget.builder(Text.literal(dlLabel),
                    btn -> startDownload())
                    .dimensions(centerX - btnW - 4, btnY, btnW, btnH)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Downloads FFmpeg from " + FfmpegBundleManager.getDownloadSourceDescription()
                                    + ".\nThe binary will be stored at " + FfmpegBundleManager.getBundleDirectory() + "."
                                    + "\nThe download is HTTPS-authenticated and (where available) verified against the upstream hash.")))
                    .build();
            this.downloadButton.active = !isDownloading;
            this.addDrawableChild(this.downloadButton);

            this.cancelButton = ButtonWidget.builder(Text.literal(isDownloading ? "Hide" : "Cancel"),
                    btn -> close())
                    .dimensions(centerX + 4, btnY, btnW, btnH)
                    .build();
            this.addDrawableChild(this.cancelButton);
        } else {
            // Manual-only (Android or unknown).
            this.openFolderButton = ButtonWidget.builder(Text.literal("Open FFmpeg Folder"),
                    btn -> openBundleFolder())
                    .dimensions(centerX - btnW - 4, btnY, btnW, btnH)
                    .build();
            this.addDrawableChild(this.openFolderButton);

            this.cancelButton = ButtonWidget.builder(Text.literal("Close"),
                    btn -> close())
                    .dimensions(centerX + 4, btnY, btnW, btnH)
                    .build();
            this.addDrawableChild(this.cancelButton);
        }

        // Secondary toolbar (always available unless a download is in flight):
        // lets the user diagnose and manually point the mod at an FFmpeg binary.
        // This is the primary Android workaround when auto-download cannot run.
        if (!isDownloading) {
            int row2Y = this.toolbarRowY;
            int gap = 6;
            int totalW = this.panelWidth - 24;
            int third = (totalW - gap * 2) / 3;
            int bx = this.panelLeft + 12;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Test FFmpeg"), b -> runTest())
                    .dimensions(bx, row2Y, third, btnH)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Re-probe every FFmpeg location and show exactly what was tried and why each failed.")))
                    .build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Paste FFmpeg Path"), b -> pastePathFromClipboard())
                    .dimensions(bx + third + gap, row2Y, third, btnH)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Set the FFmpeg path from your clipboard, e.g.\n/data/data/com.termux/files/usr/bin/ffmpeg\nThen it is tested immediately.")))
                    .build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy Termux Cmd"), b -> copyTermuxCommand())
                    .dimensions(bx + (third + gap) * 2, row2Y, totalW - (third + gap) * 2, btnH)
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                            "Copy 'pkg install ffmpeg' to the clipboard to paste into Termux.")))
                    .build());
        }

        FfmpegBundleManager.addProgressListener(this);
        this.currentProgress = FfmpegBundleManager.getLastProgress();
    }

    private void rebuildAndReinit() {
        this.clearChildren();
        this.init();
    }

    private void setClipboardText(String s) {
        if (this.client != null) {
            this.client.keyboard.setClipboard(s);
        }
    }

    private String getClipboardText() {
        return this.client == null ? "" : this.client.keyboard.getClipboard();
    }

    private void runTest() {
        this.testReport = dev.recordable.FFmpegEncoder.testFfmpegVerbose(null);
        rebuildAndReinit();
    }

    private void copyTermuxCommand() {
        setClipboardText("pkg install ffmpeg");
        this.testReport = "Copied to clipboard:\n  pkg install ffmpeg\n\n"
                + "1. Open Termux (from F-Droid) and paste + run it.\n"
                + "2. Run 'which ffmpeg' and copy the printed path.\n"
                + "3. Come back and tap 'Paste FFmpeg Path'.";
        rebuildAndReinit();
    }

    private void pastePathFromClipboard() {
        String clip = getClipboardText();
        if (clip == null || clip.isBlank()) {
            this.testReport = "Clipboard is empty.\nCopy your ffmpeg path first, e.g.\n"
                    + "/data/data/com.termux/files/usr/bin/ffmpeg";
            rebuildAndReinit();
            return;
        }
        String p = clip.trim();
        try {
            RecordableConfig c = RecordableConfig.get();
            c.ffmpegPath = p;
            c.save();
        } catch (Exception ignored) {
        }
        dev.recordable.FFmpegEncoder.invalidateDetectionCache();
        this.testReport = "Set FFmpeg path to:\n  " + p + "\n\n"
                + dev.recordable.FFmpegEncoder.testFfmpegVerbose(p);
        rebuildAndReinit();
    }

    private void rebuildLines() {
        lines.clear();
        boolean alreadyAvailable = FfmpegBundleManager.isBundledFfmpegAvailable();
        boolean autoSupported = FfmpegBundleManager.isAutoDownloadSupported();
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();

        addHeader("FFmpeg Setup");
        addBlank();

        if (alreadyAvailable) {
            addHighlight("✓ FFmpeg is installed and ready.");
            addBlank();
            addText("Location:");
            String path = FfmpegBundleManager.getBundledFfmpegPath();
            addText("  " + truncate(path == null ? "(unknown)" : path, 70));
            addBlank();
            addText("You can close this screen and start recording.");
            return;
        }

        addText("Record-able needs FFmpeg to encode video. To keep this mod");
        addText("Modrinth-friendly we do not bundle the FFmpeg binary.");
        addText("You download it once, from an official upstream, and the");
        addText("mod stores it inside your Minecraft folder.");
        addBlank();

        addHighlight("Platform: " + platform.displayName());
        if (autoSupported) {
            addText("Source:    " + FfmpegBundleManager.getDownloadSourceDescription());
            addText("Size:      " + FfmpegBundleManager.getEstimatedDownloadSize());
            addText("Saved to:  " + truncate(FfmpegBundleManager.getBundleDirectory().toString(), 60));
            addBlank();
            addHighlight("Integrity:");
            switch (platform) {
                case WINDOWS -> {
                    addText("  • Downloaded over HTTPS from gyan.dev");
                    addText("  • SHA-256 verified against gyan.dev's published");
                    addText("    .sha256 sibling file (best-effort).");
                }
                case LINUX -> {
                    addText("  • Downloaded over HTTPS from johnvansickle.com");
                    addText("  • MD5 verified against the .md5 sibling file");
                    addText("    (best-effort).");
                }
                case MACOS -> {
                    addText("  • Downloaded over HTTPS from evermeet.cx");
                    addText("  • HTTPS host authentication; no sibling hash file");
                    addText("    is published. The archive is signed upstream.");
                }
                default -> {}
            }
            addBlank();
            addText("Click 'Download FFmpeg' to start. The mod will not");
            addText("contact the internet until you do.");
        } else if (platform == PlatformUtils.Platform.ANDROID) {
            addWarning("Auto-download is not supported on Android.");
            addText("Exec-mounted, writable storage is rare on Pojav/Zalith/FCL,");
            addText("so a downloaded binary often cannot be run.");
            addBlank();
            addHighlight("Recommended (easiest):");
            addText("  1. Install Termux from F-Droid");
            addText("     ( https://f-droid.org/packages/com.termux/ )");
            addText("  2. In Termux, run:  pkg install ffmpeg");
            addText("  3. In Record-able's settings, set");
            addText("     ffmpegPath to:");
            addText("     /data/data/com.termux/files/usr/bin/ffmpeg");
            addBlank();
            addHighlight("Manual alternative:");
            addText("  Place a static arm64 ffmpeg binary at:");
            addText("    " + truncate(FfmpegBundleManager.getBundleDirectory().resolve("ffmpeg").toString(), 64));
            addText("  Then chmod +x it. Use 'Open FFmpeg Folder' below.");
        } else {
            addWarning("Auto-download not available for this platform.");
            addText(FfmpegBundleManager.getManualInstallInstructions());
        }

        // Show any prior error
        FfmpegBundleManager.Status st = FfmpegBundleManager.getStatus();
        if (st == FfmpegBundleManager.Status.ERROR) {
            addBlank();
            addError("Last error: " + (FfmpegBundleManager.getLastError() == null
                    ? "unknown" : FfmpegBundleManager.getLastError()));
            addText("If the problem persists, see manual install above.");
        }

        appendManualOverrideSection();
    }

    /**
     * Appends the "manual override" help (current configured path + how to use
     * the toolbar buttons) and the most recent Test FFmpeg / paste report.
     */
    private void appendManualOverrideSection() {
        addBlank();
        addHeader("Manual override / diagnostics");
        String configured = "";
        try {
            configured = RecordableConfig.get().ffmpegPath;
        } catch (Exception ignored) {
        }
        if (configured != null && !configured.isBlank()) {
            addHighlight("Current FFmpeg path (settings):");
            addText("  " + truncate(configured.trim(), 64));
        } else {
            addText("No manual FFmpeg path set.");
        }
        addText("\u2022 'Paste FFmpeg Path' - set it from the clipboard.");
        addText("\u2022 'Test FFmpeg' - re-probe and show what was tried.");
        addText("\u2022 'Copy Termux Cmd' - copies 'pkg install ffmpeg'.");

        if (this.testReport != null && !this.testReport.isBlank()) {
            addBlank();
            addHeader("Last test result");
            for (String raw : this.testReport.split("\n", -1)) {
                String line = raw.replace("\t", "    ");
                int color = line.contains("[OK]") || line.startsWith("RESULT: FFmpeg FOUND")
                        || line.startsWith("RESULT: OK")
                        ? HIGHLIGHT_COLOR
                        : (line.contains("[FAIL]") || line.contains("NOT FOUND") || line.startsWith("RESULT: FAILED")
                                ? ERROR_COLOR : TEXT_COLOR);
                if (line.isEmpty()) {
                    lines.add(new Line("", color, false));
                } else {
                    for (String wrapped : wrapToWidth(line, this.textWrapWidth)) {
                        lines.add(new Line(wrapped, color, false));
                    }
                }
            }
        }
    }

    private void startDownload() {
        if (FfmpegBundleManager.isDownloading()) return;
        if (this.downloadButton != null) {
            this.downloadButton.setMessage(Text.literal("Downloading…"));
            this.downloadButton.active = false;
        }
        FfmpegBundleManager.downloadAsync(success -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                this.failureShown = !success;
                // Rebuild the screen so labels/buttons reflect the new state.
                this.clearChildren();
                this.init();
            });
        });
    }

    private void openBundleFolder() {
        try {
            java.nio.file.Files.createDirectories(FfmpegBundleManager.getBundleDirectory());
            Util.getOperatingSystem().open(FfmpegBundleManager.getBundleDirectory().toFile());
        } catch (Exception e) {
            dev.recordable.RecordableMod.LOGGER.warn("[FfmpegDownloadScreen] Could not open folder: {}", e.getMessage());
        }
    }

    @Override
    public void onProgress(FfmpegBundleManager.DownloadProgress progress) {
        this.currentProgress = progress;
        // No need to call init() - render() reads currentProgress every frame.
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
        for (int i = 0; i < lines.size(); i++) {
            int y = this.panelBodyTop + i * LINE_HEIGHT - this.scrollOffset;
            // Cull lines scrolled outside the visible body region.
            if (y < this.panelBodyTop - LINE_HEIGHT || y > this.panelBodyBottom - 2) continue;
            Line line = lines.get(i);
            if (line.text.isEmpty()) continue;
            String prefix = line.bold ? "§l" : "";
            RenderHelper.drawText(context, this.textRenderer, Text.literal(prefix + line.text), textLeft, y, line.color);
        }

        // Visual scrollbar on the right edge of the body region.
        renderScrollbar(context, accent);

        // Progress bar shown while DOWNLOADING.
        if (FfmpegBundleManager.isDownloading()) {
            int barWidth = this.panelWidth - 36;
            int barHeight = 14;
            int barLeft = this.panelLeft + 18;
            int barTop = this.progressBarTop;

            context.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, PROGRESS_BG);
            context.fill(barLeft, barTop, barLeft + barWidth, barTop + 1, PROGRESS_BORDER);
            context.fill(barLeft, barTop + barHeight - 1, barLeft + barWidth, barTop + barHeight, PROGRESS_BORDER);
            context.fill(barLeft, barTop, barLeft + 1, barTop + barHeight, PROGRESS_BORDER);
            context.fill(barLeft + barWidth - 1, barTop, barLeft + barWidth, barTop + barHeight, PROGRESS_BORDER);

            double frac = currentProgress.fraction();
            if (frac > 0.0) {
                int fillWidth = (int) Math.round((barWidth - 2) * Math.min(1.0, Math.max(0.0, frac)));
                context.fill(barLeft + 1, barTop + 1, barLeft + 1 + fillWidth, barTop + barHeight - 1, accent);
            }

            String label = (currentProgress.phase() == null ? "" : capitalize(currentProgress.phase()) + " · ")
                    + currentProgress.displayBytes()
                    + (currentProgress.totalBytes() > 0 ? " (" + currentProgress.displayPercent() + ")" : "");
            RenderHelper.drawText(context, this.textRenderer, Text.literal(label),
                    barLeft, barTop - 11, TEXT_COLOR);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderScrollbar(DrawContext context, int accent) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            return;
        }
        int barLeft = this.panelLeft + this.panelWidth - SCROLLBAR_WIDTH - 2;
        int barRight = barLeft + SCROLLBAR_WIDTH;
        // Track
        context.fill(barLeft, this.panelBodyTop, barRight, this.panelBodyBottom, 0x40000000);
        // Thumb
        int thumbHeight = Math.max(28, (int) (viewportHeight * (viewportHeight / (double) this.contentHeight)));
        int available = viewportHeight - thumbHeight;
        int thumbTop = this.panelBodyTop + (available <= 0 ? 0
                : (int) ((this.scrollOffset / (double) maxScroll) * available));
        context.fill(barLeft, thumbTop, barRight, thumbTop + thumbHeight, accent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, this.contentHeight - (this.panelBodyBottom - this.panelBodyTop));
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int delta = (int) Math.round(verticalAmount * -20.0D);
        if (delta == 0) {
            delta = verticalAmount > 0 ? -20 : 20;
        }
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll > 0) {
            switch (keyCode) {
                case 264 -> { // DOWN
                    this.scrollOffset = Math.min(maxScroll, this.scrollOffset + LINE_HEIGHT);
                    return true;
                }
                case 265 -> { // UP
                    this.scrollOffset = Math.max(0, this.scrollOffset - LINE_HEIGHT);
                    return true;
                }
                case 266 -> { // PAGE_UP
                    this.scrollOffset = Math.max(0, this.scrollOffset - viewportHeight);
                    return true;
                }
                case 267 -> { // PAGE_DOWN
                    this.scrollOffset = Math.min(maxScroll, this.scrollOffset + viewportHeight);
                    return true;
                }
                default -> { /* fall through */ }
            }
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0 && isOverScrollbar(click.x(), click.y())) {
            this.draggingScrollbar = true;
            scrollToMouse(click.y());
            return true;
        }
        // Touch/mouse drag-to-pan anywhere in the body when content overflows.
        // Fall through so buttons still receive the click if it's a tap.
        if (click.button() == 0 && isContentScrollable() && isOverBody(click.x(), click.y())) {
            this.draggingBody = true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (this.draggingScrollbar && click.button() == 0) {
            scrollToMouse(click.y());
            return true;
        }
        if (this.draggingBody && click.button() == 0) {
            panBy(deltaY);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        boolean wasScrollbar = this.draggingScrollbar;
        if (click.button() == 0) {
            this.draggingScrollbar = false;
            this.draggingBody = false;
        }
        if (wasScrollbar) {
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) return false;
        int barLeft = this.panelLeft + this.panelWidth - SCROLLBAR_WIDTH - 2;
        int barRight = barLeft + SCROLLBAR_WIDTH;
        return mouseX >= barLeft - 8 && mouseX <= barRight + 6
                && mouseY >= this.panelBodyTop && mouseY <= this.panelBodyBottom;
    }

    /** True when there is more content than the visible body region can show. */
    private boolean isContentScrollable() {
        return this.contentHeight > (this.panelBodyBottom - this.panelBodyTop);
    }

    /** True when the point falls inside the scrollable body region. */
    private boolean isOverBody(double mouseX, double mouseY) {
        return mouseX >= this.panelLeft && mouseX <= this.panelLeft + this.panelWidth
                && mouseY >= this.panelBodyTop && mouseY <= this.panelBodyBottom;
    }

    /** Pans the content by a drag delta (finger/mouse down reveals earlier text). */
    private void panBy(double deltaY) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) return;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int) Math.round(deltaY)));
    }

    private void scrollToMouse(double mouseY) {
        int viewportHeight = this.panelBodyBottom - this.panelBodyTop;
        int maxScroll = Math.max(0, this.contentHeight - viewportHeight);
        if (maxScroll <= 0) return;
        int thumbHeight = Math.max(28, (int) (viewportHeight * (viewportHeight / (double) this.contentHeight)));
        int available = viewportHeight - thumbHeight;
        if (available <= 0) {
            this.scrollOffset = 0;
            return;
        }
        double rel = (mouseY - this.panelBodyTop - thumbHeight / 2.0) / available;
        rel = Math.max(0.0, Math.min(1.0, rel));
        this.scrollOffset = (int) Math.round(rel * maxScroll);
    }

    @Override
    public void removed() {
        FfmpegBundleManager.removeProgressListener(this);
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void addHeader(String text)    { addWrapped(text, HEADER_COLOR, true); }
    private void addText(String text)      { addWrapped(text, TEXT_COLOR, false); }
    private void addHighlight(String text) { addWrapped(text, HIGHLIGHT_COLOR, false); }
    private void addWarning(String text)   { addWrapped(text, WARNING_COLOR, false); }
    private void addError(String text)     { addWrapped(text, ERROR_COLOR, false); }
    private void addBlank()                { lines.add(new Line("", TEXT_COLOR, false)); }

    /**
     * Adds {@code text} as one or more {@link Line}s, wrapping to the current
     * {@link #textWrapWidth} so nothing runs off the right edge on any screen
     * size. Honors explicit {@code \n} line breaks in the source string.
     */
    private void addWrapped(String text, int color, boolean bold) {
        if (text == null || text.isEmpty()) {
            lines.add(new Line("", color, bold));
            return;
        }
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add(new Line("", color, bold));
                continue;
            }
            for (String wrapped : wrapToWidth(paragraph, this.textWrapWidth)) {
                lines.add(new Line(wrapped, color, bold));
            }
        }
    }

    /**
     * Word-wraps a single line to {@code maxWidth} pixels using the active font.
     * Preserves leading indentation on continuation lines and hard-breaks any
     * single token (e.g. a long path) that cannot fit on its own line.
     */
    private java.util.List<String> wrapToWidth(String text, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) { out.add(""); return out; }
        if (maxWidth <= 8 || this.textRenderer.getWidth(text) <= maxWidth) { out.add(text); return out; }

        int indentCount = 0;
        while (indentCount < text.length() && text.charAt(indentCount) == ' ') indentCount++;
        String indent = text.substring(0, indentCount);
        String contIndent = indent + "  ";

        String[] words = text.substring(indentCount).split(" ");
        String curIndent = indent;
        StringBuilder line = new StringBuilder(curIndent);
        boolean hasWord = false;

        for (String w : words) {
            String word = w;
            if (word.isEmpty()) continue;
            // Hard-break a word too long to fit even alone.
            while (this.textRenderer.getWidth(curIndent + word) > maxWidth && word.length() > 1) {
                if (hasWord) {
                    out.add(line.toString());
                    curIndent = contIndent;
                    line.setLength(0);
                    line.append(curIndent);
                    hasWord = false;
                }
                int fit = 1;
                while (fit < word.length()
                        && this.textRenderer.getWidth(curIndent + word.substring(0, fit + 1)) <= maxWidth) {
                    fit++;
                }
                out.add(curIndent + word.substring(0, fit));
                word = word.substring(fit);
                curIndent = contIndent;
                line.setLength(0);
                line.append(curIndent);
                hasWord = false;
            }
            if (!hasWord) {
                line.setLength(0);
                line.append(curIndent).append(word);
                hasWord = true;
            } else if (this.textRenderer.getWidth(line + " " + word) <= maxWidth) {
                line.append(" ").append(word);
            } else {
                out.add(line.toString());
                curIndent = contIndent;
                line.setLength(0);
                line.append(curIndent).append(word);
                hasWord = true;
            }
        }
        if (hasWord) out.add(line.toString());
        if (out.isEmpty()) out.add(text);
        return out;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : "…" + s.substring(s.length() - maxLen + 1);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record Line(String text, int color, boolean bold) {}
}
