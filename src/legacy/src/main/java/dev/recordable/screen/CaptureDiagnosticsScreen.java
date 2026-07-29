package dev.recordable.screen;

import dev.recordable.CaptureDiagnostics;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordingManager;
import dev.recordable.ScreenCapture;
import dev.recordable.compat.RenderHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * "Test Capture" diagnostic screen.
 *
 * <p>Surfaces the capture-health engine ({@link CaptureDiagnostics}) to the
 * user: a one-shot capture self-test, the screen-size aware detector, and the
 * live black-frame / auto-recovery statistics. The whole point is that the
 * problem where a user's screen "did not render any GUI / overlays" becomes a
 * clearly-reported FAIL with a plain-language explanation instead of a silent
 * black recording.</p>
 */
public final class CaptureDiagnosticsScreen extends Screen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int OK_COLOR = 0xFF88CC88;
    private static final int WARN_COLOR = 0xFFFFCC44;
    private static final int FAIL_COLOR = 0xFFFF6060;
    private static final int INFO_COLOR = 0xFFB8C4D0;

    private final Screen parent;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int scrollOffset;
    private int contentHeight;
    private int bodyTop;
    private int bodyBottom;

    private final List<DiagLine> lines = new ArrayList<>();

    public CaptureDiagnosticsScreen(Screen parent) {
        super(Text.literal("Capture Diagnostics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.scrollOffset = 0;

        this.panelWidth = Math.max(340, Math.min((int) (this.width * 0.80D), 600));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.05D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(300, (int) (this.height * 0.88D)));
        this.bodyTop = this.panelTop + 38;
        this.bodyBottom = this.panelBottom - 34;

        // Kick off a fresh capture self-test as soon as the screen opens.
        RecordingManager.getInstance().requestCaptureSelfTest();

        int buttonWidth = 150;
        int gap = 10;
        int totalWidth = buttonWidth + gap + 110;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.panelBottom - 28;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Run Test Again"),
                button -> RecordingManager.getInstance().requestCaptureSelfTest()
        ).dimensions(startX, buttonY, buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                button -> close()
        ).dimensions(startX + buttonWidth + gap, buttonY, 110, 20).build());
    }

    /** Gathers fresh inputs and rebuilds the diagnostic lines (called each frame). */
    private void rebuildLines() {
        lines.clear();

        MinecraftClient client = MinecraftClient.getInstance();
        int winW = 0;
        int winH = 0;
        int guiScale = 0;
        if (client != null && client.getWindow() != null) {
            winW = client.getWindow().getFramebufferWidth();
            winH = client.getWindow().getFramebufferHeight();
            try {
                guiScale = (int) client.getWindow().getScaleFactor();
            } catch (Throwable ignored) {
                guiScale = 0;
            }
        }

        int[] rt = ScreenCapture.currentRenderTargetSize();
        int rtW = rt != null ? rt[0] : -1;
        int rtH = rt != null ? rt[1] : -1;

        RecordingManager manager = RecordingManager.getInstance();
        CaptureDiagnostics.Inputs inputs = new CaptureDiagnostics.Inputs(
                winW, winH, rtW, rtH, guiScale,
                manager.isActiveOrStopping(),
                manager.getLiveCaptureStats(),
                manager.getCaptureSelfTestResult());

        List<CaptureDiagnostics.Check> checks = CaptureDiagnostics.buildReport(inputs);
        CaptureDiagnostics.Status verdict = CaptureDiagnostics.overallVerdict(checks);

        addLine(CaptureDiagnostics.verdictSummary(verdict), colorFor(verdict), true);
        addBlank();

        int detailWidth = this.panelWidth - 40;
        for (CaptureDiagnostics.Check check : checks) {
            int color = colorFor(check.status());
            addLine(statusTag(check.status()) + " " + check.label(), color, true);
            for (String wrapped : wrap(check.detail(), detailWidth)) {
                addLine("   " + wrapped, TEXT_COLOR, false);
            }
            addBlank();
        }

        this.contentHeight = lines.size() * 12 + 10;
    }

    private void addLine(String text, int color, boolean bold) {
        lines.add(new DiagLine(text, color, bold));
    }

    private void addBlank() {
        lines.add(new DiagLine("", TEXT_COLOR, false));
    }

    private static String statusTag(CaptureDiagnostics.Status status) {
        return switch (status) {
            case OK -> "[OK]";
            case WARN -> "[!]";
            case FAIL -> "[X]";
            default -> "[i]";
        };
    }

    private static int colorFor(CaptureDiagnostics.Status status) {
        return switch (status) {
            case OK -> OK_COLOR;
            case WARN -> WARN_COLOR;
            case FAIL -> FAIL_COLOR;
            default -> INFO_COLOR;
        };
    }

    /** Greedy word-wrap to a pixel width using the active font. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (this.textRenderer.getWidth(candidate) > maxWidth && current.length() > 0) {
                out.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, this.contentHeight - (this.bodyBottom - this.bodyTop));
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        rebuildLines();

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
        int clipTop = this.bodyTop;
        int clipBottom = this.bodyBottom;
        context.enableScissor(left + 1, clipTop, right - 1, clipBottom);
        for (int i = 0; i < lines.size(); i++) {
            int y = this.bodyTop + (i * 12) - this.scrollOffset;
            if (y < this.bodyTop - 12 || y > this.bodyBottom + 2) {
                continue;
            }
            DiagLine line = lines.get(i);
            if (line.text.isEmpty()) {
                continue;
            }
            String text = line.bold ? "\u00a7l" + line.text : line.text;
            RenderHelper.drawText(context, this.textRenderer, Text.literal(text), textLeft, y, line.color);
        }
        context.disableScissor();

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private record DiagLine(String text, int color, boolean bold) {}
}
