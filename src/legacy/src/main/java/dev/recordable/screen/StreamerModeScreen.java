package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.RecordableConfig;
import dev.recordable.CensorRegion;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedToggle;
import dev.recordable.theme.ThemedPanel;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * V1-0.08 Streamer Mode editor (censor upgrade).
 *
 * <p>Lets the user toggle Streamer Mode and visually place fully solid censor
 * overlays on a 16:9 preview canvas. Each region is a 100% opaque block (no
 * transparency, no pixelation, no blur) so hidden content cannot be recovered.
 * When a region is selected, its colour, optional two-colour gradient, and an
 * optional painted text label can be configured in the left panel.</p>
 *
 * <p>Regions are stored as fractions of the frame so they stay correct at any
 * recording resolution.</p>
 */
public final class StreamerModeScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING   = 22;
    private static final int PANEL_W       = 200;
    private static final double MIN_SIZE   = 0.03;

    private final Screen parent;

    // Canvas geometry (screen pixels).
    private int canvasX, canvasY, canvasW, canvasH;

    // Selection + drag state.
    private int selected = -1;
    private int dragMode = 0; // 0 none, 1 move, 2 resize, 3 create
    private double pressFx, pressFy;
    private double origX, origY;

    public StreamerModeScreen(Screen parent) {
        super(Text.translatable("screen.recordable.streamer.title"));
        this.parent = parent;
    }

    private List<CensorRegion> regions() {
        return RecordableConfig.get().censorRegions;
    }

    private CensorRegion selectedRegion() {
        List<CensorRegion> rs = regions();
        if (selected >= 0 && selected < rs.size()) {
            return rs.get(selected);
        }
        return null;
    }

    @Override
    protected void init() {
        super.init();
        computeCanvas();
        rebuildWidgets();
    }

    private void computeCanvas() {
        int areaX = PANEL_W + 12;
        int areaW = this.width - areaX - 16;
        int areaY = 40;
        int areaH = this.height - areaY - 16;
        int w = areaW;
        int h = w * 9 / 16;
        if (h > areaH) { h = areaH; w = h * 16 / 9; }
        this.canvasW = Math.max(80, w);
        this.canvasH = Math.max(45, h);
        this.canvasX = areaX + (areaW - this.canvasW) / 2;
        this.canvasY = areaY + (areaH - this.canvasH) / 2;
    }

    /** Rebuilds the left-panel widgets, reflecting the current selection. */
    private void rebuildWidgets() {
        this.clearChildren();
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { close(); return; }

        int wx = 14;
        int ww = PANEL_W - 24;
        int y  = 32;

        addDrawableChild(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Streamer Mode", config.streamerModeEnabled, v -> {
            config.streamerModeEnabled = v; config.save();
        }));
        y += ROW_SPACING;

        addDrawableChild(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Show Preview", config.streamerShowCensorPreview, v -> {
            config.streamerShowCensorPreview = v; config.save();
        }));
        y += ROW_SPACING;

        var bakeInOverlayToggle = ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Bake in Overlay", config.bakeInOverlay, v -> {
            config.bakeInOverlay = v; config.save();
        });
        bakeInOverlayToggle.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                "Controls whether your Streamer Mode censor blocks are baked into the recording.\n\n"
                        + "OFF (default): the recording stays clean (no censor in the saved video). "
                        + "Instead, the censor blocks appear as a live on-screen overlay (like a watermark) "
                        + "that obstructs scoreboards, coordinates, GUI elements and inventories. "
                        + "Regions are layered (they stack), and you can show/hide the overlay with the "
                        + "\"Toggle Censor Overlay\" hotkey (set it in Options > Controls).\n\n"
                        + "ON: the censor is baked into your recording. "
                        + "It may also appear on your live screen (controlled by Show Preview).")));
        addDrawableChild(bakeInOverlayToggle);
        y += ROW_SPACING;

        addDrawableChild(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                Text.literal("Add Region"), b -> { addRegion(); rebuildWidgets(); }));
        y += ROW_SPACING;

        addDrawableChild(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                Text.literal("Edit On Screen (Live)"),
                b -> this.client.setScreen(new CensorOverlayEditorScreen(this))));
        y += ROW_SPACING;

        CensorRegion sel = selectedRegion();
        if (sel == null) {
            // No region selected: just a Clear All control. Performance options
            // now live in their own Performance category screen.
            addDrawableChild(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Text.literal("Clear All"), b -> {
                regions().clear(); selected = -1; config.save(); rebuildWidgets();
            }));
            y += ROW_SPACING + 4;
        } else {
            // Region property editor.
            addDrawableChild(CycleButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Text.literal("Style: " + sel.style.name()), b -> {
                sel.style = nextStyle(sel.style);
                config.save();
                rebuildWidgets();
            }, b -> {
                sel.style = prevStyle(sel.style);
                config.save();
                rebuildWidgets();
            }));
            y += ROW_SPACING;

            addDrawableChild(new ColorPickerWidget(this.textRenderer, wx, y, ww, WIDGET_HEIGHT,
                    Text.literal("Color"), toHex(sel.color), hex -> {
                sel.color = fromHex(hex); config.save();
            }));
            y += ROW_SPACING;

            if (sel.style == CensorRegion.Style.GRADIENT) {
                addDrawableChild(new ColorPickerWidget(this.textRenderer, wx, y, ww, WIDGET_HEIGHT,
                        Text.literal("Color 2"), toHex(sel.colorEnd), hex -> {
                    sel.colorEnd = fromHex(hex); config.save();
                }));
                y += ROW_SPACING;

                addDrawableChild(CycleButton.create(wx, y, ww, WIDGET_HEIGHT,
                        Text.literal("Gradient: " + sel.gradientDirection.name()), b -> {
                    sel.gradientDirection = nextDir(sel.gradientDirection);
                    config.save();
                    b.setMessage(Text.literal("Gradient: " + sel.gradientDirection.name()));
                }, b -> {
                    sel.gradientDirection = prevDir(sel.gradientDirection);
                    config.save();
                    b.setMessage(Text.literal("Gradient: " + sel.gradientDirection.name()));
                }));
                y += ROW_SPACING;
            }

            addDrawableChild(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                    "Show Text", sel.showLabel, v -> {
                sel.showLabel = v; config.save(); rebuildWidgets();
            }));
            y += ROW_SPACING;

            TextFieldWidget labelField = new TextFieldWidget(this.textRenderer, wx, y, ww, 18,
                    Text.literal("Label"));
            labelField.setMaxLength(40);
            labelField.setText(sel.label == null ? "" : sel.label);
            labelField.setChangedListener(v -> {
                sel.label = (v == null || v.isBlank()) ? "Censor" : v;
                config.save();
            });
            addDrawableChild(labelField);
            y += ROW_SPACING;

            if (sel.showLabel) {
                addDrawableChild(new ColorPickerWidget(this.textRenderer, wx, y, ww, WIDGET_HEIGHT,
                        Text.literal("Text Color"), toHex(sel.textColor), hex -> {
                    sel.textColor = fromHex(hex); config.save();
                }));
                y += ROW_SPACING;
            }

            addDrawableChild(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Text.literal("Remove Selected"), b -> { removeSelected(); rebuildWidgets(); }));
            y += ROW_SPACING + 4;
        }

        addDrawableChild(ThemedButton.create(wx, this.height - 28, ww, WIDGET_HEIGHT,
                Text.literal("Done"), b -> close()));
    }

    // --- Region helpers ---------------------------------------------------

    private void addRegion() {
        RecordableConfig config = RecordableConfig.get();
        CensorRegion r = new CensorRegion(0.375, 0.45, 0.25, 0.10,
                parseStyle(config.streamerDefaultCensorStyle), "Censor");
        regions().add(r);
        selected = regions().size() - 1;
        config.save();
    }

    private void removeSelected() {
        if (selected >= 0 && selected < regions().size()) {
            regions().remove(selected);
            selected = -1;
            RecordableConfig.get().save();
        }
    }

    private static CensorRegion.Style nextStyle(CensorRegion.Style s) {
        CensorRegion.Style[] all = CensorRegion.Style.values();
        return all[(s.ordinal() + 1) % all.length];
    }

    private static CensorRegion.GradientDirection nextDir(CensorRegion.GradientDirection d) {
        CensorRegion.GradientDirection[] all = CensorRegion.GradientDirection.values();
        return all[(d.ordinal() + 1) % all.length];
    }

    private static CensorRegion.Style prevStyle(CensorRegion.Style s) {
        CensorRegion.Style[] all = CensorRegion.Style.values();
        return all[(s.ordinal() - 1 + all.length) % all.length];
    }

    private static CensorRegion.GradientDirection prevDir(CensorRegion.GradientDirection d) {
        CensorRegion.GradientDirection[] all = CensorRegion.GradientDirection.values();
        return all[(d.ordinal() - 1 + all.length) % all.length];
    }

    private static CensorRegion.Style parseStyle(String s) {
        if (s != null) {
            try { return CensorRegion.Style.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        return CensorRegion.Style.SOLID;
    }

    private static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static int fromHex(String s) {
        try {
            String t = (s != null && s.startsWith("#")) ? s.substring(1) : s;
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // --- Mouse handling ---------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return canvasPress((int) mouseX, (int) mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragMode != 0) { canvasDrag((int) mouseX, (int) mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragMode != 0) { canvasRelease(); return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean canvasPress(int mx, int my, int button) {
        if (button != 0) return false;
        if (mx < canvasX || mx > canvasX + canvasW || my < canvasY || my > canvasY + canvasH) {
            return false;
        }
        double fx = (mx - canvasX) / (double) canvasW;
        double fy = (my - canvasY) / (double) canvasH;
        List<CensorRegion> rs = regions();
        int prevSelected = selected;

        // Resize handle of the selected region takes priority.
        if (selected >= 0 && selected < rs.size()) {
            CensorRegion r = rs.get(selected);
            int hx = canvasX + (int) ((r.x + r.width) * canvasW);
            int hy = canvasY + (int) ((r.y + r.height) * canvasH);
            if (Math.abs(mx - hx) <= 6 && Math.abs(my - hy) <= 6) {
                dragMode = 2; pressFx = fx; pressFy = fy;
                origX = r.x; origY = r.y;
                return true;
            }
        }

        // Topmost region under the cursor.
        for (int i = rs.size() - 1; i >= 0; i--) {
            CensorRegion r = rs.get(i);
            if (fx >= r.x && fx <= r.x + r.width && fy >= r.y && fy <= r.y + r.height) {
                selected = i;
                dragMode = 1; pressFx = fx; pressFy = fy;
                origX = r.x; origY = r.y;
                if (selected != prevSelected) rebuildWidgets();
                return true;
            }
        }

        // Empty canvas: create a new region anchored here.
        RecordableConfig config = RecordableConfig.get();
        CensorRegion nr = new CensorRegion(clamp(fx, 0, 1 - MIN_SIZE), clamp(fy, 0, 1 - MIN_SIZE),
                MIN_SIZE, MIN_SIZE, parseStyle(config.streamerDefaultCensorStyle), "Censor");
        rs.add(nr);
        selected = rs.size() - 1;
        dragMode = 3; pressFx = nr.x; pressFy = nr.y;
        origX = nr.x; origY = nr.y;
        rebuildWidgets();
        return true;
    }

    private void canvasDrag(int mx, int my) {
        if (selected < 0 || selected >= regions().size()) return;
        CensorRegion r = regions().get(selected);
        double fx = clamp((mx - canvasX) / (double) canvasW, 0, 1);
        double fy = clamp((my - canvasY) / (double) canvasH, 0, 1);
        if (dragMode == 1) {
            double dx = fx - pressFx, dy = fy - pressFy;
            r.x = clamp(origX + dx, 0, 1 - r.width);
            r.y = clamp(origY + dy, 0, 1 - r.height);
        } else if (dragMode == 2) {
            r.width  = clamp(fx - r.x, MIN_SIZE, 1 - r.x);
            r.height = clamp(fy - r.y, MIN_SIZE, 1 - r.y);
        } else if (dragMode == 3) {
            double x0 = Math.min(pressFx, fx), y0 = Math.min(pressFy, fy);
            double x1 = Math.max(pressFx, fx), y1 = Math.max(pressFy, fy);
            r.x = x0; r.y = y0;
            r.width  = Math.max(MIN_SIZE, x1 - x0);
            r.height = Math.max(MIN_SIZE, y1 - y0);
        }
    }

    private void canvasRelease() {
        if (selected >= 0 && selected < regions().size()) {
            regions().get(selected).sanitize();
        }
        dragMode = 0;
        RecordableConfig.get().save();
    }

    // --- Render -----------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.draw();
        TextRenderer tr = this.textRenderer;
        RecordableConfig config = RecordableConfig.get();
        if (config == null) return;

        ThemedPanel.drawPanel(context, 6, 6, PANEL_W, this.height - 6);
        context.drawCenteredTextWithShadow(tr, this.title, PANEL_W / 2, 14, 0xFFFFFFFF);

        // Canvas frame.
        context.fill(canvasX - 2, canvasY - 2, canvasX + canvasW + 2, canvasY + canvasH + 2, 0xFF2A2A2A);
        context.fill(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 0xFF101014);
        // Thirds guides.
        context.fill(canvasX + canvasW / 3, canvasY, canvasX + canvasW / 3 + 1, canvasY + canvasH, 0x22FFFFFF);
        context.fill(canvasX + canvasW * 2 / 3, canvasY, canvasX + canvasW * 2 / 3 + 1, canvasY + canvasH, 0x22FFFFFF);
        context.fill(canvasX, canvasY + canvasH / 3, canvasX + canvasW, canvasY + canvasH / 3 + 1, 0x22FFFFFF);
        context.fill(canvasX, canvasY + canvasH * 2 / 3, canvasX + canvasW, canvasY + canvasH * 2 / 3 + 1, 0x22FFFFFF);

        List<CensorRegion> rs = regions();
        for (int i = 0; i < rs.size(); i++) {
            CensorRegion r = rs.get(i);
            int x0 = canvasX + (int) (r.x * canvasW);
            int y0 = canvasY + (int) (r.y * canvasH);
            int x1 = canvasX + (int) ((r.x + r.width) * canvasW);
            int y1 = canvasY + (int) ((r.y + r.height) * canvasH);

            // Fully opaque preview matching the recorded output.
            if (r.style == CensorRegion.Style.GRADIENT) {
                context.fillGradient(x0, y0, x1, y1, 0xFF000000 | (r.color & 0xFFFFFF),
                        0xFF000000 | (r.colorEnd & 0xFFFFFF));
            } else {
                context.fill(x0, y0, x1, y1, 0xFF000000 | (r.color & 0xFFFFFF));
            }

            int bc = (i == selected) ? 0xFF44FF44 : 0xFFFFFFFF;
            context.fill(x0, y0, x1, y0 + 1, bc);
            context.fill(x0, y1 - 1, x1, y1, bc);
            context.fill(x0, y0, x0 + 1, y1, bc);
            context.fill(x1 - 1, y0, x1, y1, bc);

            if (r.showLabel && r.label != null && !r.label.isBlank()) {
                RenderHelper.drawText(context, tr, Text.literal(r.label),
                        x0 + 3, y0 + 3, 0xFF000000 | (r.textColor & 0xFFFFFF));
            } else {
                RenderHelper.drawText(context, tr, Text.literal(r.style.name()),
                        x0 + 3, y0 + 3, 0xFFFFFFFF);
            }
            if (i == selected) {
                context.fill(x1 - 5, y1 - 5, x1, y1, 0xFFFF8844);
            }
        }

        String info = rs.isEmpty()
                ? "Drag on the canvas to add a censor box"
                : "Regions: " + rs.size() + (selected >= 0 ? "  (selected #" + (selected + 1) + ")" : "");
        RenderHelper.drawText(context, tr, Text.literal(info), canvasX, canvasY + canvasH + 4, 0xFFB0B0B0);
        if (!config.streamerModeEnabled) {
            RenderHelper.drawText(context, tr, Text.literal("Streamer Mode is OFF - regions are saved but not applied"),
                    canvasX, canvasY - 12, 0xFFFFAA55);
        }
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
