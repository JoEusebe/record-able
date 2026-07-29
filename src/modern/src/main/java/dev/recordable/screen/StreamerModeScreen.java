package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.CensorRegion;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedToggle;
import dev.recordable.theme.ThemedPanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * V1-0.08 Streamer Mode editor (censor upgrade, modern variant).
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

    private int canvasX, canvasY, canvasW, canvasH;

    private int selected = -1;
    private int dragMode = 0; // 0 none, 1 move, 2 resize, 3 create
    private double pressFx, pressFy;
    private double origX, origY, origW, origH;

    public StreamerModeScreen(Screen parent) {
        super(Component.translatable("screen.recordable.streamer.title"));
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
        rebuildControls();
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
    private void rebuildControls() {
        this.clearWidgets();
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { onClose(); return; }

        int wx = 14;
        int ww = PANEL_W - 24;
        int y  = 32;

        addRenderableWidget(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Streamer Mode", config.streamerModeEnabled, v -> {
            config.streamerModeEnabled = v; config.save();
        }));
        y += ROW_SPACING;

        addRenderableWidget(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Show Preview", config.streamerShowCensorPreview, v -> {
            config.streamerShowCensorPreview = v; config.save();
        }));
        y += ROW_SPACING;

        var bakeInOverlayToggle = ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                "Bake in Overlay", config.bakeInOverlay, v -> {
            config.bakeInOverlay = v; config.save();
        });
        bakeInOverlayToggle.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "Controls whether your Streamer Mode censor blocks are baked into the recording.\n\n"
                        + "OFF (default): the recording stays clean (no censor in the saved video). "
                        + "Instead, the censor blocks appear as a live on-screen overlay (like a watermark) "
                        + "that obstructs scoreboards, coordinates, GUI elements and inventories. "
                        + "Regions are layered (they stack), and you can show/hide the overlay with the "
                        + "\"Toggle Censor Overlay\" hotkey (set it in Options > Controls).\n\n"
                        + "ON: the censor is baked into your recording. "
                        + "It may also appear on your live screen (controlled by Show Preview).")));
        addRenderableWidget(bakeInOverlayToggle);
        y += ROW_SPACING;

        addRenderableWidget(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                Component.literal("Add Region"), b -> { addRegion(); rebuildControls(); }));
        y += ROW_SPACING;

        addRenderableWidget(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                Component.literal("Edit On Screen (Live)"),
                b -> this.minecraft.setScreenAndShow(new CensorOverlayEditorScreen(this))));
        y += ROW_SPACING;

        CensorRegion sel = selectedRegion();
        if (sel == null) {
            addRenderableWidget(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Component.literal("Clear All"), b -> {
                regions().clear(); selected = -1; config.save(); rebuildControls();
            }));
            y += ROW_SPACING + 4;
        } else {
            addRenderableWidget(CycleButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Component.literal("Style: " + sel.style.name()), b -> {
                sel.style = nextStyle(sel.style);
                config.save();
                rebuildControls();
            }, b -> {
                sel.style = prevStyle(sel.style);
                config.save();
                rebuildControls();
            }));
            y += ROW_SPACING;

            addRenderableWidget(new ColorPickerWidget(this.font, wx, y, ww, WIDGET_HEIGHT,
                    Component.literal("Color"), toHex(sel.color), hex -> {
                sel.color = fromHex(hex); config.save();
            }));
            y += ROW_SPACING;

            if (sel.style == CensorRegion.Style.GRADIENT) {
                addRenderableWidget(new ColorPickerWidget(this.font, wx, y, ww, WIDGET_HEIGHT,
                        Component.literal("Color 2"), toHex(sel.colorEnd), hex -> {
                    sel.colorEnd = fromHex(hex); config.save();
                }));
                y += ROW_SPACING;

                addRenderableWidget(CycleButton.create(wx, y, ww, WIDGET_HEIGHT,
                        Component.literal("Gradient: " + sel.gradientDirection.name()), b -> {
                    sel.gradientDirection = nextDir(sel.gradientDirection);
                    config.save();
                    b.setMessage(Component.literal("Gradient: " + sel.gradientDirection.name()));
                }, b -> {
                    sel.gradientDirection = prevDir(sel.gradientDirection);
                    config.save();
                    b.setMessage(Component.literal("Gradient: " + sel.gradientDirection.name()));
                }));
                y += ROW_SPACING;
            }

            addRenderableWidget(ThemedToggle.create(wx, y, ww, WIDGET_HEIGHT,
                    "Show Text", sel.showLabel, v -> {
                sel.showLabel = v; config.save(); rebuildControls();
            }));
            y += ROW_SPACING;

            EditBox labelField = new EditBox(this.font, wx, y, ww, 18, Component.literal("Label"));
            labelField.setMaxLength(40);
            labelField.setValue(sel.label == null ? "" : sel.label);
            labelField.setResponder(v -> {
                sel.label = (v == null || v.isBlank()) ? "Censor" : v;
                config.save();
            });
            addRenderableWidget(labelField);
            y += ROW_SPACING;

            if (sel.showLabel) {
                addRenderableWidget(new ColorPickerWidget(this.font, wx, y, ww, WIDGET_HEIGHT,
                        Component.literal("Text Color"), toHex(sel.textColor), hex -> {
                    sel.textColor = fromHex(hex); config.save();
                }));
                y += ROW_SPACING;
            }

            addRenderableWidget(ThemedButton.create(wx, y, ww, WIDGET_HEIGHT,
                    Component.literal("Remove Selected"), b -> { removeSelected(); rebuildControls(); }));
            y += ROW_SPACING + 4;
        }

        addRenderableWidget(ThemedButton.create(wx, this.height - 28, ww, WIDGET_HEIGHT,
                Component.literal("Done"), b -> onClose()));
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
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        return canvasPress((int) click.x(), (int) click.y(), click.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        if (dragMode != 0) { canvasDrag((int) click.x(), (int) click.y()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragMode != 0) { canvasRelease(); return true; }
        return super.mouseReleased(click);
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

        if (selected >= 0 && selected < rs.size()) {
            CensorRegion r = rs.get(selected);
            int hx = canvasX + (int) ((r.x + r.width) * canvasW);
            int hy = canvasY + (int) ((r.y + r.height) * canvasH);
            if (Math.abs(mx - hx) <= 6 && Math.abs(my - hy) <= 6) {
                dragMode = 2; pressFx = fx; pressFy = fy;
                origX = r.x; origY = r.y; origW = r.width; origH = r.height;
                return true;
            }
        }

        for (int i = rs.size() - 1; i >= 0; i--) {
            CensorRegion r = rs.get(i);
            if (fx >= r.x && fx <= r.x + r.width && fy >= r.y && fy <= r.y + r.height) {
                selected = i;
                dragMode = 1; pressFx = fx; pressFy = fy;
                origX = r.x; origY = r.y; origW = r.width; origH = r.height;
                if (selected != prevSelected) rebuildControls();
                return true;
            }
        }

        RecordableConfig config = RecordableConfig.get();
        CensorRegion nr = new CensorRegion(clamp(fx, 0, 1 - MIN_SIZE), clamp(fy, 0, 1 - MIN_SIZE),
                MIN_SIZE, MIN_SIZE, parseStyle(config.streamerDefaultCensorStyle), "Censor");
        rs.add(nr);
        selected = rs.size() - 1;
        dragMode = 3; pressFx = nr.x; pressFy = nr.y;
        origX = nr.x; origY = nr.y; origW = MIN_SIZE; origH = MIN_SIZE;
        rebuildControls();
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

    /** Draws a fully opaque gradient rectangle using interpolated strips. */
    private static void drawGradientRect(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1,
                                         int colorStart, int colorEnd,
                                         CensorRegion.GradientDirection dir) {
        int sr = (colorStart >> 16) & 0xFF, sg = (colorStart >> 8) & 0xFF, sb = colorStart & 0xFF;
        int er = (colorEnd >> 16) & 0xFF, eg = (colorEnd >> 8) & 0xFF, eb = colorEnd & 0xFF;
        boolean vertical = dir == CensorRegion.GradientDirection.VERTICAL;
        int span = vertical ? (y1 - y0) : (x1 - x0);
        if (span <= 0) return;
        int strips = Math.min(span, 48);
        for (int s = 0; s < strips; s++) {
            double t = strips == 1 ? 0.0 : (double) s / (strips - 1);
            int cr = (int) Math.round(sr + (er - sr) * t);
            int cg = (int) Math.round(sg + (eg - sg) * t);
            int cb = (int) Math.round(sb + (eb - sb) * t);
            int col = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
            int a = y0 + (int) Math.round((double) s / strips * (y1 - y0));
            int aEnd = y0 + (int) Math.round((double) (s + 1) / strips * (y1 - y0));
            int b = x0 + (int) Math.round((double) s / strips * (x1 - x0));
            int bEnd = x0 + (int) Math.round((double) (s + 1) / strips * (x1 - x0));
            if (vertical) {
                context.fill(x0, a, x1, aEnd, col);
            } else {
                context.fill(b, y0, bEnd, y1, col);
            }
        }
    }

    // --- Render -----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.extractMenuBackground(context);
        Font tr = this.font;
        RecordableConfig config = RecordableConfig.get();
        if (config == null) { super.extractRenderState(context, mouseX, mouseY, delta); return; }

        ThemedPanel.drawPanel(context, 6, 6, PANEL_W, this.height - 6);
        context.centeredText(tr, this.title, PANEL_W / 2, 14, 0xFFFFFFFF);

        context.fill(canvasX - 2, canvasY - 2, canvasX + canvasW + 2, canvasY + canvasH + 2, 0xFF2A2A2A);
        context.fill(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 0xFF101014);
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

            if (r.style == CensorRegion.Style.GRADIENT) {
                drawGradientRect(context, x0, y0, x1, y1, r.color & 0xFFFFFF, r.colorEnd & 0xFFFFFF,
                        r.gradientDirection);
            } else {
                context.fill(x0, y0, x1, y1, 0xFF000000 | (r.color & 0xFFFFFF));
            }

            int bc = (i == selected) ? 0xFF44FF44 : 0xFFFFFFFF;
            context.fill(x0, y0, x1, y0 + 1, bc);
            context.fill(x0, y1 - 1, x1, y1, bc);
            context.fill(x0, y0, x0 + 1, y1, bc);
            context.fill(x1 - 1, y0, x1, y1, bc);

            if (r.showLabel && r.label != null && !r.label.isBlank()) {
                context.text(tr, Component.literal(r.label), x0 + 3, y0 + 3,
                        0xFF000000 | (r.textColor & 0xFFFFFF), true);
            } else {
                context.text(tr, Component.literal(r.style.name()), x0 + 3, y0 + 3, 0xFFFFFFFF, true);
            }
            if (i == selected) {
                context.fill(x1 - 5, y1 - 5, x1, y1, 0xFFFF8844);
            }
        }

        String info = rs.isEmpty()
                ? "Drag on the canvas to add a censor box"
                : "Regions: " + rs.size() + (selected >= 0 ? "  (selected #" + (selected + 1) + ")" : "");
        context.text(tr, Component.literal(info), canvasX, canvasY + canvasH + 4, 0xFFB0B0B0, true);
        if (!config.streamerModeEnabled) {
            context.text(tr, Component.literal("Streamer Mode is OFF - regions are saved but not applied"),
                    canvasX, canvasY - 12, 0xFFFFAA55, true);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
