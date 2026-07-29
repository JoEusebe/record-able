package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkSlot;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import dev.recordable.theme.ThemeEngine;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Visual drag-and-drop overlay positioning screen with a unified side panel
 * containing collapsible Layers and Opacity sections.
 *
 * <p>Applies the same overlayScale as {@code RecordingOverlay} so WYSIWYG.</p>
 */
public final class OverlayPositionScreen extends Screen {

    // ─── Visual constants ────────────────────────────────────────────────
    private static final int IDLE_BORDER      = 0x88FFFFFF;
    private static final int HOVER_BORDER     = 0xCCFFFF00;
    private static final int SELECTED_BORDER  = 0xFF44FF44;
    private static final int SELECTED_FILL    = 0x2244FF44;
    private static final int RESIZE_HANDLE_COLOR = 0xFFFF8844;
    private static final int LABEL_BG         = 0xCC000000;
    private static final int COORD_COLOR      = 0xFFAAFFAA;
    private static final int HEADER_COLOR     = 0xFFFFFFFF;
    private static final int HINT_COLOR       = 0xFFB0B0B0;
    private static final int PANEL_BG         = 0xBB0E0E16;  // fallback
    private static final int PANEL_BORDER     = 0xFF3A3A3A;  // fallback
    private static final int SECTION_BG       = 0xFF181824;  // fallback
    private static final int SECTION_HOVER    = 0xFF222236;  // fallback
    private static final int ACCENT           = 0xFF6688CC;  // fallback

    /** Theme-aware panel bg */
    private int tPanelBg()     { return ThemeEngine.get().colors().panelBackground; }
    private int tPanelBorder() { return ThemeEngine.get().colors().panelBorder; }
    private int tSectionBg()   { return ThemeEngine.get().colors().sectionBackground; }
    private int tSectionHov()  { return ThemeEngine.get().colors().sectionHover; }
    private int tAccent()      { return ThemeEngine.get().colors().accent; }

    private static final int RESIZE_HANDLE_SIZE = 6;
    private static final int PANEL_W           = 154;
    private static final int ROW_H             = 16;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private final Screen parent;

    // ─── Snapshot for ESC-cancel ─────────────────────────────────────────
    private int origPlayRecX, origPlayRecY;
    private int origTimestampOffsetX, origTimestampY;
    private int origSpX, origSpOffsetY;
    private int origPerfOffsetX, origPerfOffsetY;
    private int origDetailsOffsetX, origDetailsOffsetY;
    private int origCornersX, origCornersY, origCornersW, origCornersH;
    private int origPlayRecW, origPlayRecH, origTimestampW, origTimestampH;
    private int origSpW, origSpH, origPerfW, origPerfH, origDetailsW, origDetailsH;
    private String origVhsPlayColor, origVhsRecTextColor, origVhsRecDotColor;
    private String origVhsBracketColor, origVhsTimestampColor, origVhsDateColor, origVhsSpColor;
    private int origPlayRecOpacity, origTimestampOpacity, origCornersOpacity;
    private int origSpOpacity, origDetailsOpacity, origPerfOpacity;
    private String origLayerOrder;
    private boolean origPlayRecVisible, origTimestampVisible, origCornersVisible;
    private boolean origSpVisible, origDetailsVisible, origPerfVisible;
    private int origClassicX, origClassicY, origSynthX, origSynthY;
    private boolean origClassicVisible, origSynthVisible;

    // ─── Draggable elements ──────────────────────────────────────────────
    private final List<DraggableElement> elements = new ArrayList<>();
    private DraggableElement hoveredElement;
    private DraggableElement draggedElement;
    private int dragOffsetX, dragOffsetY;
    private boolean cancelled;

    // ─── Resize state ────────────────────────────────────────────────────
    private enum ResizeEdge { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private ResizeEdge activeResize = ResizeEdge.NONE;
    private ResizeEdge hoveredResize = ResizeEdge.NONE;
    private DraggableElement resizeElement;
    private int resizeOrigX, resizeOrigY, resizeOrigW, resizeOrigH;

    // ─── Unified panel state ─────────────────────────────────────────────
    private boolean panelOpen = true;
    private int panelScroll = 0;
    private boolean sectionLayersOpen = true;
    private boolean sectionOpacityOpen = false;
    private boolean sectionWatermarksOpen = true;


    private final List<OpacityEntry> opacityEntries = new ArrayList<>();
    private final List<String> layerOrder = new ArrayList<>();

    // ─── Opacity slider drag state ───────────────────────────────────────
    private OpacityEntry draggingOpacity;

    // ─── Overlay-scale-aware dimensions ──────────────────────────────────
    private int vw, vh;
    private float overlayScale = 1.0f;
    private double dragMouseX, dragMouseY;

    public OverlayPositionScreen(Screen parent) {
        super(Component.translatable("screen.recordable.position_editor.title"));
        this.parent = parent;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();
        RecordableConfig config = RecordableConfig.get();
        overlayScale = Math.max(0.5f, Math.min(2.0f, config.overlayScale / 100.0f));

        snapshotAll(config);
        buildOpacityEntries(config);
        initLayerOrder(config);

        int btnW = 60, btnH = 20, gap = 4;
        int totalW = btnW * 4 + gap * 3;
        int startX = (this.width - totalW) / 2;
        int btnY = this.height - btnH - 6;

        addRenderableWidget(Button.builder(
                Component.translatable("screen.recordable.position_editor.done"), b -> saveAndClose()
        ).bounds(startX, btnY, btnW, btnH).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.recordable.position_editor.reset_all"), b -> resetAllDefaults()
        ).bounds(startX + btnW + gap, btnY, btnW, btnH).build());
        addRenderableWidget(Button.builder(
                Component.literal(panelOpen ? "▶ Panel" : "◀ Panel"), b -> {
                    panelOpen = !panelOpen;
                    b.setMessage(Component.literal(panelOpen ? "▶ Panel" : "◀ Panel"));
                }
        ).bounds(startX + (btnW + gap) * 2, btnY, btnW, btnH).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.recordable.position_editor.cancel"), b -> cancelAndClose()
        ).bounds(startX + (btnW + gap) * 3, btnY, btnW, btnH).build());
    }

    // ─── Snapshot / Restore ──────────────────────────────────────────────

    private void snapshotAll(RecordableConfig c) {
        origPlayRecX = c.hudPlayRecX;           origPlayRecY = c.hudPlayRecY;
        origTimestampOffsetX = c.hudTimestampOffsetX; origTimestampY = c.hudTimestampY;
        origSpX = c.hudSpX;                     origSpOffsetY = c.hudSpOffsetY;
        origPerfOffsetX = c.hudPerfOffsetX;     origPerfOffsetY = c.hudPerfOffsetY;
        origDetailsOffsetX = c.hudDetailsOffsetX; origDetailsOffsetY = c.hudDetailsOffsetY;
        origCornersX = c.hudCornersX;           origCornersY = c.hudCornersY;
        origCornersW = c.hudCornersWidth;       origCornersH = c.hudCornersHeight;
        origPlayRecW = c.hudPlayRecW;           origPlayRecH = c.hudPlayRecH;
        origTimestampW = c.hudTimestampW;       origTimestampH = c.hudTimestampH;
        origSpW = c.hudSpW;                     origSpH = c.hudSpH;
        origPerfW = c.hudPerfW;                 origPerfH = c.hudPerfH;
        origDetailsW = c.hudDetailsW;           origDetailsH = c.hudDetailsH;
        origVhsPlayColor = c.vhsPlayColor;      origVhsRecTextColor = c.vhsRecTextColor;
        origVhsRecDotColor = c.vhsRecDotColor;  origVhsBracketColor = c.vhsBracketColor;
        origVhsTimestampColor = c.vhsTimestampColor;
        origVhsDateColor = c.vhsDateColor;       origVhsSpColor = c.vhsSpColor;
        origPlayRecOpacity = c.hudPlayRecOpacity; origTimestampOpacity = c.hudTimestampOpacity;
        origCornersOpacity = c.hudCornersOpacity; origSpOpacity = c.hudSpOpacity;
        origDetailsOpacity = c.hudDetailsOpacity; origPerfOpacity = c.hudPerfOpacity;
        origLayerOrder = c.hudLayerOrder;
        origPlayRecVisible = c.hudPlayRecVisible; origTimestampVisible = c.hudTimestampVisible;
        origCornersVisible = c.hudCornersVisible; origSpVisible = c.hudSpVisible;
        origDetailsVisible = c.hudDetailsVisible; origPerfVisible = c.hudPerfVisible;
        origClassicX = c.hudClassicX; origClassicY = c.hudClassicY;
        origSynthX = c.hudSynthX; origSynthY = c.hudSynthY;
        origClassicVisible = c.hudClassicVisible; origSynthVisible = c.hudSynthVisible;
    }

    private void restoreAll(RecordableConfig c) {
        c.hudPlayRecX = origPlayRecX;           c.hudPlayRecY = origPlayRecY;
        c.hudTimestampOffsetX = origTimestampOffsetX; c.hudTimestampY = origTimestampY;
        c.hudSpX = origSpX;                     c.hudSpOffsetY = origSpOffsetY;
        c.hudPerfOffsetX = origPerfOffsetX;     c.hudPerfOffsetY = origPerfOffsetY;
        c.hudDetailsOffsetX = origDetailsOffsetX; c.hudDetailsOffsetY = origDetailsOffsetY;
        c.hudCornersX = origCornersX;           c.hudCornersY = origCornersY;
        c.hudCornersWidth = origCornersW;       c.hudCornersHeight = origCornersH;
        c.hudPlayRecW = origPlayRecW;           c.hudPlayRecH = origPlayRecH;
        c.hudTimestampW = origTimestampW;       c.hudTimestampH = origTimestampH;
        c.hudSpW = origSpW;                     c.hudSpH = origSpH;
        c.hudPerfW = origPerfW;                 c.hudPerfH = origPerfH;
        c.hudDetailsW = origDetailsW;           c.hudDetailsH = origDetailsH;
        c.vhsPlayColor = origVhsPlayColor;       c.vhsRecTextColor = origVhsRecTextColor;
        c.vhsRecDotColor = origVhsRecDotColor;   c.vhsBracketColor = origVhsBracketColor;
        c.vhsTimestampColor = origVhsTimestampColor;
        c.vhsDateColor = origVhsDateColor;       c.vhsSpColor = origVhsSpColor;
        c.hudPlayRecOpacity = origPlayRecOpacity; c.hudTimestampOpacity = origTimestampOpacity;
        c.hudCornersOpacity = origCornersOpacity; c.hudSpOpacity = origSpOpacity;
        c.hudDetailsOpacity = origDetailsOpacity; c.hudPerfOpacity = origPerfOpacity;
        c.hudLayerOrder = origLayerOrder;
        c.hudPlayRecVisible = origPlayRecVisible; c.hudTimestampVisible = origTimestampVisible;
        c.hudCornersVisible = origCornersVisible; c.hudSpVisible = origSpVisible;
        c.hudDetailsVisible = origDetailsVisible; c.hudPerfVisible = origPerfVisible;
        c.hudClassicX = origClassicX; c.hudClassicY = origClassicY;
        c.hudSynthX = origSynthX; c.hudSynthY = origSynthY;
        c.hudClassicVisible = origClassicVisible; c.hudSynthVisible = origSynthVisible;
        c.save();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Defaults
    // ═══════════════════════════════════════════════════════════════════════

    private static final int DEF_PLAY_REC_X = 80, DEF_PLAY_REC_Y = 14;
    private static final int DEF_TS_OFFSET_X = 14, DEF_TS_Y = 14;
    private static final int DEF_SP_X = 80, DEF_SP_OFFSET_Y = 24;
    private static final int DEF_PERF_OFFSET_X = 8, DEF_PERF_OFFSET_Y = 80;
    private static final int DEF_DETAILS_OFFSET_X = 14, DEF_DETAILS_OFFSET_Y = 14;
    private static final int DEF_CORNERS_X = 68, DEF_CORNERS_Y = 4;
    private static final int DEF_CORNERS_W = 100, DEF_CORNERS_H = 48;

    private static final String DEF_LAYER_ORDER = RecordableConfig.defaultLayerOrder();

    // ═══════════════════════════════════════════════════════════════════════
    // Panel data models
    // ═══════════════════════════════════════════════════════════════════════

    private static final class OpacityEntry {
        final String id, label;
        final Supplier<Integer> getter;
        final Consumer<Integer> setter;
        OpacityEntry(String id, String label, Supplier<Integer> getter, Consumer<Integer> setter) {
            this.id = id; this.label = label; this.getter = getter; this.setter = setter;
        }
    }

    /** Get a compact icon/prefix for each element type. */
    private static String elementIcon(String id) {
        return switch (id) {
            case "PLAY/REC" -> "\u25CF"; // ●
            case "Timestamp" -> "\u23F1"; // ⏱
            case "Details" -> "\u2139"; // ℹ
            case "SP" -> "\u25B6"; // ▶
            case "Perf" -> "\u2261"; // ≡
            case "Corners" -> "\u2B1C"; // ⬜
            case "Mic" -> "\uD83C\uDFA4"; // 🎤
            case "Classic" -> "\u25A4"; // ▤
            case "Synthwave" -> "\u25A4"; // ▤
            case "Filter:VHS", "Filter:LCD_MOIRE", "Filter:CRT" -> "\u25A3"; // ▣
            default -> "\u2022"; // •
        };
    }

    /** Get the short display name for a layer ID. */
    private static String layerDisplayName(String id) {
        return switch (id) {
            case "Filter:VHS" -> "VHS Filter";
            case "Filter:LCD_MOIRE" -> "LCD Moire Filter";
            case "Filter:CRT" -> "CRT Filter";
            default -> id;
        };
    }

    private void buildOpacityEntries(RecordableConfig config) {
        opacityEntries.clear();
        RecordableConfig.OverlayStyleHud opStyle = config.overlayStyleHud != null
                ? config.overlayStyleHud : RecordableConfig.OverlayStyleHud.CLASSIC;
        // Per-element HUD opacity only applies to the VHS overlay - the only style that
        // lays out individual HUD elements. Classic / Synthwave / None draw a fixed (or no)
        // panel, so for those the Opacity section just exposes the live filter intensities.
        if (opStyle == RecordableConfig.OverlayStyleHud.VHS) {
            opacityEntries.add(new OpacityEntry("PLAY/REC", "REC",
                    () -> config.hudPlayRecOpacity, v -> config.hudPlayRecOpacity = v));
            opacityEntries.add(new OpacityEntry("Timestamp", "Time",
                    () -> config.hudTimestampOpacity, v -> config.hudTimestampOpacity = v));
            opacityEntries.add(new OpacityEntry("Corners", "Corners",
                    () -> config.hudCornersOpacity, v -> config.hudCornersOpacity = v));
            opacityEntries.add(new OpacityEntry("SP", "SP",
                    () -> config.hudSpOpacity, v -> config.hudSpOpacity = v));
            opacityEntries.add(new OpacityEntry("Details", "Details",
                    () -> config.hudDetailsOpacity, v -> config.hudDetailsOpacity = v));
            opacityEntries.add(new OpacityEntry("Perf", "Perf",
                    () -> config.hudPerfOpacity, v -> config.hudPerfOpacity = v));
        }
        // Live-preview filter intensities (reuse the opacity slider widget).
        opacityEntries.add(new OpacityEntry("Filter:VHS", "VHS",
                () -> config.filterVhsIntensity, v -> config.filterVhsIntensity = v));
        opacityEntries.add(new OpacityEntry("Filter:LCD_MOIRE", "LCD Moire",
                () -> config.filterLcdMoireIntensity, v -> config.filterLcdMoireIntensity = v));
        opacityEntries.add(new OpacityEntry("Filter:CRT", "CRT",
                () -> config.filterCrtIntensity, v -> config.filterCrtIntensity = v));
    }

    private static final String[] ALL_LAYERS =
            RecordableConfig.allLayerIds().toArray(new String[0]);

    private void initLayerOrder(RecordableConfig config) {
        layerOrder.clear();
        java.util.Set<String> validSet = java.util.Set.of(ALL_LAYERS);
        if (config.hudLayerOrder != null && !config.hudLayerOrder.isBlank()) {
            for (String part : config.hudLayerOrder.split(",")) {
                String id = part.trim();
                // Only add recognized layer IDs - skip removed/unknown entries
                if (!id.isEmpty() && validSet.contains(id) && !layerOrder.contains(id)) {
                    layerOrder.add(id);
                }
            }
        }
        for (String id : ALL_LAYERS) { if (!layerOrder.contains(id)) layerOrder.add(id); }
    }

    private void saveLayerOrder() {
        RecordableConfig config = RecordableConfig.get();
        config.hudLayerOrder = String.join(",", layerOrder);
        config.save();
    }

    /**
     * The subset of {@link #layerOrder} that the current overlay style actually
     * renders, in order. The editor (preview canvas + Layers panel) only shows
     * these so the elements change dynamically with the selected overlay style.
     *
     * <p>{@link #layerOrder} itself always keeps the full canonical list so that
     * saving never discards the order of elements belonging to other styles.</p>
     */
    private List<String> shownLayers() {
        RecordableConfig config = RecordableConfig.get();
        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud != null
                ? config.overlayStyleHud : RecordableConfig.OverlayStyleHud.CLASSIC;
        java.util.Set<String> allowed =
                new java.util.HashSet<>(RecordableConfig.layerIdsForStyle(style));
        List<String> out = new ArrayList<>();
        for (String id : layerOrder) {
            if (allowed.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * Reorder a shown layer (display index {@code i}) by {@code dir} (-1 up / +1 down).
     * The swap is applied to the full {@link #layerOrder} so the relative order of
     * elements hidden from the current style is preserved.
     */
    private void moveShownLayer(List<String> shown, int i, int dir) {
        int j = i + dir;
        if (i < 0 || j < 0 || i >= shown.size() || j >= shown.size()) return;
        int ia = layerOrder.indexOf(shown.get(i));
        int ib = layerOrder.indexOf(shown.get(j));
        if (ia < 0 || ib < 0) return;
        String tmp = layerOrder.get(ia);
        layerOrder.set(ia, layerOrder.get(ib));
        layerOrder.set(ib, tmp);
        saveLayerOrder();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // ── Background ──
        // Draw a manual dark background instead of extractMenuBackground() to
        // avoid potential z-ordering / render-state issues where the menu
        // background can cover subsequently batched fill() calls.
        drawPreviewBackground(context);

        RecordableConfig config = RecordableConfig.get();
        if (config == null || this.minecraft == null) { super.extractRenderState(context, mouseX, mouseY, delta); return; }

        Font tr = this.font;
        overlayScale = Math.max(0.5f, Math.min(2.0f, config.overlayScale / 100.0f));
        vw = (int) (this.width / overlayScale);
        vh = (int) (this.height / overlayScale);

        // ── Scaled overlay space ──
        context.pose().pushMatrix();
        context.pose().scale(overlayScale, overlayScale);

        int smx = (int) (mouseX / overlayScale);
        int smy = (int) (mouseY / overlayScale);

        drawGridGuides(context);
        rebuildElements(config, tr);

        // Hover detection - check in reverse layer order (topmost first) and skip hidden
        hoveredElement = null;
        hoveredResize = ResizeEdge.NONE;
        boolean overPanel = panelOpen && mouseX >= this.width - PANEL_W - 10;
        if (draggedElement == null && activeResize == ResizeEdge.NONE && !overPanel) {
            // Build ordered list matching render order, then check reversed (top layer first)
            List<DraggableElement> ordered = new ArrayList<>();
            for (String layerId : shownLayers()) {
                if (!config.isElementVisible(layerId)) continue;
                for (DraggableElement el : elements) { if (el.id.equals(layerId)) { ordered.add(el); break; } }
            }
            // Check resize handles (top layer first)
            for (int i = ordered.size() - 1; i >= 0; i--) {
                DraggableElement el = ordered.get(i);
                if (el.resizable) {
                    ResizeEdge edge = hitTestResizeHandle(el, smx, smy);
                    if (edge != ResizeEdge.NONE) { hoveredResize = edge; hoveredElement = el; break; }
                }
            }
            // Check body hit (top layer first)
            if (hoveredResize == ResizeEdge.NONE) {
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    DraggableElement el = ordered.get(i);
                    if (smx >= el.x && smx <= el.x + el.w && smy >= el.y && smy <= el.y + el.h) {
                        // Corners element: only hover when near an actual corner bracket zone (~40px)
                        if (el.id.equals("Corners")) {
                            int cz = 40;
                            boolean nearTL = smx < el.x + cz && smy < el.y + cz;
                            boolean nearTR = smx > el.x + el.w - cz && smy < el.y + cz;
                            boolean nearBL = smx < el.x + cz && smy > el.y + el.h - cz;
                            boolean nearBR = smx > el.x + el.w - cz && smy > el.y + el.h - cz;
                            if (!(nearTL || nearTR || nearBL || nearBR)) continue;
                        }
                        hoveredElement = el; break;
                    }
                }
            }
        }

        // Watermark hover (topmost first) - separate pass (not in layerOrder)
        if (hoveredElement == null && draggedElement == null && activeResize == ResizeEdge.NONE && !overPanel) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                DraggableElement el = elements.get(i);
                if (el.wmIndex < 0) continue;
                if (smx >= el.x && smx <= el.x + el.w && smy >= el.y && smy <= el.y + el.h) { hoveredElement = el; break; }
            }
        }
        // Render in layer order (hidden elements rendered as ghosts)
        for (String layerId : shownLayers()) {
            boolean visible = config.isElementVisible(layerId);
            for (DraggableElement el : elements) {
                if (el.id.equals(layerId)) { renderElement(context, tr, el, smx, smy, visible); break; }
            }
        }
        // Render watermark layers (always-on, independent of layerOrder)
        for (DraggableElement el : elements) {
            if (el.wmIndex >= 0) renderWatermarkElement(context, tr, el, smx, smy);
        }
        renderOverlayPreview(context, config, tr);
        context.pose().popMatrix();

        // ── Screen-space UI ──
        if (panelOpen) renderPanel(context, tr, mouseX, mouseY);

        // Header
        context.centeredText(tr, this.title, this.width / 2, 4, HEADER_COLOR);
        String hint = activeResize != ResizeEdge.NONE ? "Drag to resize · Release to confirm"
                : draggedElement != null ? Component.translatable("screen.recordable.position_editor.hint_dragging").getString()
                : Component.translatable("screen.recordable.position_editor.hint_idle").getString();
        context.centeredText(tr, Component.literal(hint), this.width / 2, 15, HINT_COLOR);
        if (overlayScale != 1.0f) {
            String s = "Scale " + Math.round(overlayScale * 100) + "%";
            context.centeredText(tr, Component.literal(s), this.width / 2, 26, 0xFF777744);
        }

        // Coord tooltip
        if (draggedElement != null || activeResize != ResizeEdge.NONE) {
            DraggableElement el = draggedElement != null ? draggedElement : resizeElement;
            if (el != null) {
                String coords = activeResize != ResizeEdge.NONE
                        ? el.id + " " + el.w + "×" + el.h
                        : el.id + " " + el.getDisplayCoords();
                int cw = tr.width(coords) + 8;
                int cx = mouseX + 14, cy = mouseY - 14;
                if (cx + cw > this.width) cx = mouseX - cw - 4;
                if (cy < 0) cy = mouseY + 18;
                context.fill(cx - 2, cy - 2, cx + cw, cy + 12, LABEL_BG);
                context.text(tr, Component.literal(coords), cx + 2, cy, COORD_COLOR, true);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    /**
     * Draw a simple dark gradient background for the overlay editor.
     * Provides a clean backdrop for previewing overlays without the overhead
     * of a large bundled texture.
     */
    private void drawPreviewBackground(GuiGraphicsExtractor context) {
        context.fill(0, 0, this.width, this.height, 0xFF1A1A1A);
        context.fill(0, 0, this.width, this.height, 0x66000000);
    }

    private void drawGridGuides(GuiGraphicsExtractor context) {
        int cx = vw / 2, cy = vh / 2;
        // Center cross - slightly brighter so it's visible on the dark background
        context.fill(cx, 0, cx + 1, vh, 0x44FFFFFF);
        context.fill(0, cy, vw, cy + 1, 0x44FFFFFF);
        // Rule-of-thirds grid
        context.fill(vw / 3, 0, vw / 3 + 1, vh, 0x22FFFFFF);
        context.fill(vw * 2 / 3, 0, vw * 2 / 3 + 1, vh, 0x22FFFFFF);
        context.fill(0, vh / 3, vw, vh / 3 + 1, 0x22FFFFFF);
        context.fill(0, vh * 2 / 3, vw, vh * 2 / 3 + 1, 0x22FFFFFF);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Element model
    // ═══════════════════════════════════════════════════════════════════════

    private enum AnchorMode { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, RECT }

    private static final class DraggableElement {
        final String id, label;
        final AnchorMode anchor;
        int x, y, w, h, autoW, autoH;
        int wmIndex = -1; // >=0 -> watermark slot index
        boolean resizable = true;
        DraggableElement(String id, String label, AnchorMode anchor) {
            this.id = id; this.label = label; this.anchor = anchor;
        }
        String getDisplayCoords() {
            return switch (anchor) {
                case TOP_LEFT -> x + "," + y;
                case TOP_RIGHT -> "←" + x + " " + y;
                case BOTTOM_LEFT -> x + " ↑" + y;
                case BOTTOM_RIGHT -> "←" + x + " ↑" + y;
                case RECT -> x + "," + y + " " + w + "×" + h;
            };
        }
    }

    // ===================================================================
    // Watermark layers (V1-0.06): draggable preview + sidebar toggles
    // ===================================================================

    private String watermarkPlayerName() {
        return (this.minecraft != null && this.minecraft.player != null)
                ? this.minecraft.player.getName().getString() : "Player";
    }

    private String watermarkPreviewText(WatermarkSlot slot) {
        if (slot == null) return "Watermark";
        String txt = slot.kind == WatermarkSlot.Kind.IMAGE
                ? ("\u25A8 " + (slot.name != null ? slot.name : "Image"))
                : slot.resolveText(watermarkPlayerName());
        if (txt == null || txt.isEmpty()) txt = slot.name != null ? slot.name : "Watermark";
        return txt;
    }

    private int[] computeWatermarkPos(WatermarkSlot slot, int w, int h) {
        int pad = slot.padding;
        int x, y;
        switch (slot.position) {
            case CUSTOM -> { return new int[]{ slot.customX, slot.customY }; }
            case TOP_LEFT -> { x = pad; y = pad; }
            case TOP_CENTER -> { x = (vw - w) / 2; y = pad; }
            case TOP_RIGHT -> { x = vw - w - pad; y = pad; }
            case MIDDLE_LEFT -> { x = pad; y = (vh - h) / 2; }
            case CENTER -> { x = (vw - w) / 2; y = (vh - h) / 2; }
            case MIDDLE_RIGHT -> { x = vw - w - pad; y = (vh - h) / 2; }
            case BOTTOM_LEFT -> { x = pad; y = vh - h - pad; }
            case BOTTOM_CENTER -> { x = (vw - w) / 2; y = vh - h - pad; }
            case BOTTOM_RIGHT -> { x = vw - w - pad; y = vh - h - pad; }
            default -> { x = vw - w - pad; y = vh - h - pad; }
        }
        return new int[]{ x, y };
    }

    private void buildWatermarkElements(RecordableConfig config, Font tr) {
        if (config.watermarkSlots == null) return;
        for (int i = 0; i < config.watermarkSlots.size(); i++) {
            WatermarkSlot slot = config.watermarkSlots.get(i);
            if (slot == null || !slot.enabled) continue;
            DraggableElement el = new DraggableElement("WM:" + i, slot.name != null ? slot.name : "Watermark", AnchorMode.TOP_LEFT);
            el.wmIndex = i;
            el.resizable = false;
            String txt = watermarkPreviewText(slot);
            float f = slot.scale / 100.0f;
            int tw = Math.max(8, Math.round(tr.width(txt) * f));
            int th = Math.max(8, Math.round(9 * f));
            el.w = tw + 4; el.h = th + 2;
            el.autoW = el.w; el.autoH = el.h;
            int[] pos = computeWatermarkPos(slot, el.w, el.h);
            el.x = Math.max(0, Math.min(Math.max(0, vw - el.w), pos[0]));
            el.y = Math.max(0, Math.min(Math.max(0, vh - el.h), pos[1]));
            elements.add(el);
        }
    }

    private static int parseWatermarkColor(String hex, int opacityPct) {
        int rgb = 0xFFFFFF; int a = 255;
        try {
            String h = hex == null ? "" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 8) { long v = Long.parseLong(h, 16); a = (int) ((v >> 24) & 0xFF); rgb = (int) (v & 0xFFFFFF); }
            else if (h.length() == 6) { rgb = (int) Long.parseLong(h, 16); }
        } catch (Exception ignored) {}
        int op = Math.max(0, Math.min(100, opacityPct));
        int alpha = (int) (a * (op / 100.0));
        if (alpha < 40) alpha = 40;
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private void renderWatermarkElement(GuiGraphicsExtractor ctx, Font tr, DraggableElement el, int mx, int my) {
        RecordableConfig config = RecordableConfig.get();
        if (el.wmIndex < 0 || config.watermarkSlots == null || el.wmIndex >= config.watermarkSlots.size()) return;
        WatermarkSlot slot = config.watermarkSlots.get(el.wmIndex);
        boolean isDragged = el == draggedElement;
        boolean isHovered = el == hoveredElement;
        int bc = isDragged ? SELECTED_BORDER : (isHovered ? HOVER_BORDER : 0x66FFFFFF);
        int fc = isDragged ? SELECTED_FILL : (isHovered ? 0x18FFFF00 : 0x06FFFFFF);
        ctx.fill(el.x, el.y, el.x + el.w, el.y + el.h, fc);
        ctx.fill(el.x, el.y, el.x + el.w, el.y + 1, bc);
        ctx.fill(el.x, el.y + el.h - 1, el.x + el.w, el.y + el.h, bc);
        ctx.fill(el.x, el.y, el.x + 1, el.y + el.h, bc);
        ctx.fill(el.x + el.w - 1, el.y, el.x + el.w, el.y + el.h, bc);
        String txt = tr.plainSubstrByWidth(watermarkPreviewText(slot), el.w);
        int color = parseWatermarkColor(slot.textColor, slot.opacity);
        ctx.text(tr, Component.literal(txt), el.x + 2, el.y + 2, color, true);
        String tag = "\u25A4 " + el.label;
        int lw = tr.width(tag) + 4;
        int lx = el.x, ly = el.y - 10; if (ly < 0) ly = el.y + el.h + 1;
        ctx.fill(lx, ly, lx + lw, ly + 9, LABEL_BG);
        ctx.text(tr, Component.literal(tag), lx + 2, ly + 1, bc, true);
    }

    private void openWatermarkEditor(int index) {
        if (this.minecraft != null) { this.minecraft.setScreenAndShow(new WatermarkScreen(this)); }
    }

    private void rebuildElements(RecordableConfig config, Font tr) {
        elements.clear();
        buildWatermarkElements(config, tr);
        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud != null
                ? config.overlayStyleHud : RecordableConfig.OverlayStyleHud.CLASSIC;
        boolean isVhs = style == RecordableConfig.OverlayStyleHud.VHS;

        // PLAY/REC
        {
            DraggableElement el = new DraggableElement("PLAY/REC", "PLAY/REC", AnchorMode.TOP_LEFT);
            int textH = isVhs && config.vhsShowPlay ? 24 : 12;
            el.autoW = Math.max(60, tr.width("PLAY \u25B6") + 12); el.autoH = textH;
            el.w = config.hudPlayRecW > 0 ? config.hudPlayRecW : el.autoW;
            el.h = config.hudPlayRecH > 0 ? config.hudPlayRecH : el.autoH;
            el.x = config.hudPlayRecX; el.y = config.hudPlayRecY;
            elements.add(el);
        }
        // Timestamp
        {
            DraggableElement el = new DraggableElement("Timestamp", "Timestamp", AnchorMode.TOP_RIGHT);
            el.autoW = tr.width("00:12:34") + 8; el.autoH = 12;
            el.w = config.hudTimestampW > 0 ? config.hudTimestampW : el.autoW;
            el.h = config.hudTimestampH > 0 ? config.hudTimestampH : el.autoH;
            el.x = vw - config.hudTimestampOffsetX - el.w; el.y = config.hudTimestampY;
            elements.add(el);
        }
        // Corners
        {
            DraggableElement el = new DraggableElement("Corners", "Corners", AnchorMode.RECT);
            el.x = config.hudCornersX; el.y = config.hudCornersY;
            el.w = config.hudCornersWidth; el.h = config.hudCornersHeight;
            el.autoW = 100; el.autoH = 48;
            elements.add(el);
        }
        // SP
        {
            DraggableElement el = new DraggableElement("SP", "SP", AnchorMode.BOTTOM_LEFT);
            el.autoW = tr.width("SP") + 8; el.autoH = 12;
            el.w = config.hudSpW > 0 ? config.hudSpW : el.autoW;
            el.h = config.hudSpH > 0 ? config.hudSpH : el.autoH;
            el.x = config.hudSpX; el.y = vh - config.hudSpOffsetY;
            elements.add(el);
        }
        // Details
        {
            DraggableElement el = new DraggableElement("Details", "Details", AnchorMode.BOTTOM_RIGHT);
            int ch = 0, cw = 80;
            if (config.vhsShowDate) ch += 22;
            if (config.vhsShowTapeCounter) ch += 11;
            if (config.vhsShowAudioMeter) ch += 12;
            if (config.vhsShowBattery) ch += 13;
            if (ch < 20) ch = 22;
            el.autoW = cw; el.autoH = ch;
            el.w = config.hudDetailsW > 0 ? config.hudDetailsW : el.autoW;
            el.h = config.hudDetailsH > 0 ? config.hudDetailsH : el.autoH;
            el.x = vw - config.hudDetailsOffsetX - el.w;
            el.y = vh - config.hudDetailsOffsetY - el.h;
            elements.add(el);
        }
        // Perf
        {
            DraggableElement el = new DraggableElement("Perf", "Perf", AnchorMode.BOTTOM_RIGHT);
            String[] pL = {"Cap 60 | Enc 60 FPS", "Mem 1024 MiB | Drop 0", "Queue: 0/240 | 100%"};
            int mw = 0; for (String l : pL) mw = Math.max(mw, tr.width(l));
            el.autoW = mw + 10; el.autoH = 8 + pL.length * 10;
            el.w = config.hudPerfW > 0 ? config.hudPerfW : el.autoW;
            el.h = config.hudPerfH > 0 ? config.hudPerfH : el.autoH;
            el.x = vw - config.hudPerfOffsetX - el.w;
            el.y = vh - config.hudPerfOffsetY - el.h;
            elements.add(el);
        }
        // Classic single-panel element (only shown for the Classic overlay style).
        {
            DraggableElement el = new DraggableElement("Classic", "Classic", AnchorMode.TOP_LEFT);
            String[] lines = { "REC 00:12:34", "60 FPS  drop 0", "Size: 12.3 MB", "1920x1080  Queue: 0/240 (OK)" };
            int maxW = 0; for (String l : lines) maxW = Math.max(maxW, tr.width(l));
            el.autoW = maxW + 22; el.autoH = 12 + lines.length * 11;
            el.w = el.autoW; el.h = el.autoH;
            el.resizable = false;
            int[] cp = config.classicPanelPos(vw, vh, el.w, el.h, 10);
            el.x = cp[0]; el.y = cp[1];
            elements.add(el);
        }
        // Synthwave single-panel element (only shown for the Synthwave overlay style).
        {
            DraggableElement el = new DraggableElement("Synthwave", "Synthwave", AnchorMode.TOP_LEFT);
            el.autoW = tr.width("REC 00:12:34") + 22; el.autoH = 16;
            el.w = el.autoW; el.h = el.autoH;
            el.resizable = false;
            int[] sp = config.synthPanelPos(vw, vh, el.w, el.h, 0, 0);
            el.x = sp[0]; el.y = sp[1];
            elements.add(el);
        }
        // Mic (always available, independent of overlay style)
        {
            DraggableElement el = new DraggableElement("Mic", "Mic", AnchorMode.TOP_LEFT);
            String micLabel = "\uD83C\uDFA4 MIC (PTT)";
            el.autoW = tr.width(micLabel) + 16; el.autoH = 13;
            el.w = el.autoW; el.h = el.autoH;
            el.resizable = false;
            el.x = config.hudMicX < 0 ? (vw - el.w) / 2 : config.hudMicX;
            el.y = config.hudMicY;
            elements.add(el);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Resize handle hit testing
    // ═══════════════════════════════════════════════════════════════════════

    private ResizeEdge hitTestResizeHandle(DraggableElement el, int mx, int my) {
        if (!el.resizable) return ResizeEdge.NONE;
        int hs = RESIZE_HANDLE_SIZE;
        if (mx >= el.x - hs && mx <= el.x + hs && my >= el.y - hs && my <= el.y + hs) return ResizeEdge.TOP_LEFT;
        if (mx >= el.x + el.w - hs && mx <= el.x + el.w + hs && my >= el.y - hs && my <= el.y + hs) return ResizeEdge.TOP_RIGHT;
        if (mx >= el.x - hs && mx <= el.x + hs && my >= el.y + el.h - hs && my <= el.y + el.h + hs) return ResizeEdge.BOTTOM_LEFT;
        if (mx >= el.x + el.w - hs && mx <= el.x + el.w + hs && my >= el.y + el.h - hs && my <= el.y + el.h + hs) return ResizeEdge.BOTTOM_RIGHT;
        return ResizeEdge.NONE;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Element rendering
    // ═══════════════════════════════════════════════════════════════════════

    private void renderElement(GuiGraphicsExtractor ctx, Font tr, DraggableElement el, int mx, int my, boolean visible) {
        boolean isDragged = el == draggedElement;
        boolean isHovered = el == hoveredElement || el == resizeElement;
        boolean isResizing = activeResize != ResizeEdge.NONE && el == resizeElement;

        // Hidden elements: ghosted appearance
        if (!visible) {
            int ghostBorder = 0x33FF4444;
            int ghostFill = 0x08FF4444;
            ctx.fill(el.x, el.y, el.x + el.w, el.y + el.h, ghostFill);
            ctx.fill(el.x, el.y, el.x + el.w, el.y + 1, ghostBorder);
            ctx.fill(el.x, el.y + el.h - 1, el.x + el.w, el.y + el.h, ghostBorder);
            ctx.fill(el.x, el.y, el.x + 1, el.y + el.h, ghostBorder);
            ctx.fill(el.x + el.w - 1, el.y, el.x + el.w, el.y + el.h, ghostBorder);
            // Faint label with strikethrough effect
            String label = "\u2298 " + el.id; // ⊘ + name
            int lw = tr.width(label) + 4;
            int lx = el.x, ly = el.y - 10;
            if (ly < 0) ly = el.y + el.h + 1;
            ctx.fill(lx, ly, lx + lw, ly + 9, 0x66000000);
            ctx.text(tr, Component.literal(label), lx + 2, ly + 1, 0x55FF6666, true);
            return;
        }

        int bc = (isDragged || isResizing) ? SELECTED_BORDER : (isHovered ? HOVER_BORDER : IDLE_BORDER);
        int fc = (isDragged || isResizing) ? SELECTED_FILL : (isHovered ? 0x18FFFF00 : 0x08FFFFFF);

        ctx.fill(el.x, el.y, el.x + el.w, el.y + el.h, fc);
        ctx.fill(el.x, el.y, el.x + el.w, el.y + 1, bc);
        ctx.fill(el.x, el.y + el.h - 1, el.x + el.w, el.y + el.h, bc);
        ctx.fill(el.x, el.y, el.x + 1, el.y + el.h, bc);
        ctx.fill(el.x + el.w - 1, el.y, el.x + el.w, el.y + el.h, bc);

        if (el.resizable) {
            int hs = RESIZE_HANDLE_SIZE;
            int hc = 0x88FFFFFF;
            drawHandle(ctx, el.x - hs/2, el.y - hs/2, hs, hoveredResize == ResizeEdge.TOP_LEFT && isHovered ? RESIZE_HANDLE_COLOR : hc);
            drawHandle(ctx, el.x + el.w - hs/2, el.y - hs/2, hs, hoveredResize == ResizeEdge.TOP_RIGHT && isHovered ? RESIZE_HANDLE_COLOR : hc);
            drawHandle(ctx, el.x - hs/2, el.y + el.h - hs/2, hs, hoveredResize == ResizeEdge.BOTTOM_LEFT && isHovered ? RESIZE_HANDLE_COLOR : hc);
            drawHandle(ctx, el.x + el.w - hs/2, el.y + el.h - hs/2, hs, hoveredResize == ResizeEdge.BOTTOM_RIGHT && isHovered ? RESIZE_HANDLE_COLOR : hc);
        }

        // Compact label with icon
        String icon = elementIcon(el.id);
        String label = icon + " " + el.id;
        int lw = tr.width(label) + 4;
        int lx = el.x, ly = el.y - 10;
        if (ly < 0) ly = el.y + el.h + 1;
        ctx.fill(lx, ly, lx + lw, ly + 9, LABEL_BG);
        ctx.text(tr, Component.literal(label), lx + 2, ly + 1, bc, true);
    }

    private static void drawHandle(GuiGraphicsExtractor ctx, int x, int y, int s, int c) {
        ctx.fill(x, y, x + s, y + s, c);
        ctx.fill(x, y, x + s, y + 1, 0xAA000000);
        ctx.fill(x, y + s - 1, x + s, y + s, 0xAA000000);
        ctx.fill(x, y, x + 1, y + s, 0xAA000000);
        ctx.fill(x + s - 1, y, x + s, y + s, 0xAA000000);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Unified right panel with collapsible sections
    // ═══════════════════════════════════════════════════════════════════════

    private void renderPanel(GuiGraphicsExtractor ctx, Font tr, int mx, int my) {
        RecordableConfig config = RecordableConfig.get();
        int px = this.width - PANEL_W - 4;
        int py = 36;
        int maxH = this.height - 68;

        // Compute content height
        int contentH = 0;
        contentH += ROW_H; // Layers header
        if (sectionLayersOpen) {
            contentH += shownLayers().size() * ROW_H;
        }
        contentH += 2; // divider
        contentH += 2; // divider
        contentH += ROW_H; // Opacity header
        if (sectionOpacityOpen) contentH += opacityEntries.size() * (ROW_H - 1);
        contentH += 2; // divider before watermarks
        contentH += ROW_H; // Watermarks header
        if (sectionWatermarksOpen) {
            int wmRows = (config.watermarkSlots == null || config.watermarkSlots.isEmpty()) ? 1 : config.watermarkSlots.size();
            contentH += wmRows * ROW_H;
            contentH += ROW_H; // open editor row
        }
        contentH += 4; // padding

        int panelH = Math.min(maxH, contentH + 4);

        // Panel bg with rounded-feel border (themed)
        ctx.fill(px - 1, py - 1, px + PANEL_W + 1, py + panelH + 1, tPanelBorder());
        ctx.fill(px, py, px + PANEL_W, py + panelH, tPanelBg());

        int y = py + 2 - panelScroll;
        int innerW = PANEL_W - 6;
        int left = px + 3;

        // ── LAYERS section ──
        y = renderSectionHeader(ctx, tr, "\u25BE Layers", "\u25B8 Layers", sectionLayersOpen, left, y, innerW, mx, my, py, py + panelH);
        if (sectionLayersOpen) {
            List<String> shown = shownLayers();
            for (int i = 0; i < shown.size(); i++) {
                String id = shown.get(i);
                boolean isVisible = config.isElementVisible(id);

                // Main layer row
                if (y >= py && y + ROW_H <= py + panelH) {
                    boolean isHoveredEl = hoveredElement != null && hoveredElement.id.equals(id);
                    boolean rowHov = mx >= left && mx <= left + innerW && my >= y && my < y + ROW_H;

                    // Row background
                    if (isHoveredEl) {
                        ctx.fill(left, y, left + innerW, y + ROW_H - 1, 0x22FFFF44);
                    } else if (rowHov) {
                        ctx.fill(left, y, left + innerW, y + ROW_H - 1, 0x12FFFFFF);
                    }

                    // Eye toggle (leftmost)
                    int eyeX = left + 1;
                    boolean eyeHov = mx >= eyeX && mx <= eyeX + 10 && my >= y && my < y + ROW_H;
                    String eyeIcon = isVisible ? "\u25C9" : "\u25CE"; // ◉ visible, ◎ hidden
                    int eyeColor = isVisible
                            ? (eyeHov ? 0xFFAAFFAA : 0xFF66BB66)
                            : (eyeHov ? 0xFFFF8888 : 0xFF884444);
                    ctx.text(tr, Component.literal(eyeIcon), eyeX, y + 3, eyeColor, true);

                    // Element icon + name
                    String icon = elementIcon(id);
                    String displayName = layerDisplayName(id);
                    int nameColor = isVisible ? (isHoveredEl ? 0xFFFFFF88 : 0xFFCCCCCC) : 0xFF666666;
                    String display = icon + " " + tr.plainSubstrByWidth(displayName, innerW - 42);
                    ctx.text(tr, Component.literal(display), left + 13, y + 3, nameColor, true);

                    // Reorder arrows (rightmost)
                    int ax = left + innerW - 14;
                    if (i > 0) {
                        boolean hu = mx >= ax && mx <= ax + 10 && my >= y && my <= y + 7;
                        ctx.text(tr, Component.literal("\u25B2"), ax, y + 0, hu ? 0xFFFFFF44 : 0xFF555555, true);
                    }
                    if (i < shown.size() - 1) {
                        boolean hd = mx >= ax && mx <= ax + 10 && my >= y + 8 && my <= y + ROW_H;
                        ctx.text(tr, Component.literal("\u25BC"), ax, y + 8, hd ? 0xFFFFFF44 : 0xFF555555, true);
                    }
                }
                y += ROW_H;
            }
        }

        // ── OPACITY section ──
        y = renderSectionHeader(ctx, tr, "\u25BE Opacity", "\u25B8 Opacity", sectionOpacityOpen, left, y, innerW, mx, my, py, py + panelH);
        if (sectionOpacityOpen) {
            for (OpacityEntry entry : opacityEntries) {
                if (y >= py && y + ROW_H - 2 <= py + panelH) {
                    renderOpacityRow(ctx, tr, entry, left, y, innerW, mx, my);
                }
                y += ROW_H - 1;
            }
        }

        // Divider before watermarks
        if (y >= py && y + 2 <= py + panelH) {
            ctx.fill(left + 4, y, left + innerW - 4, y + 1, 0x33FFFFFF);
        }
        y += 2;

        // WATERMARKS section
        y = renderSectionHeader(ctx, tr, "\u25BE Watermarks", "\u25B8 Watermarks", sectionWatermarksOpen, left, y, innerW, mx, my, py, py + panelH);
        if (sectionWatermarksOpen) {
            java.util.List<WatermarkSlot> slots = config.watermarkSlots;
            if (slots == null || slots.isEmpty()) {
                if (y >= py && y + ROW_H <= py + panelH) {
                    ctx.text(tr, Component.literal("  (none - add below)"), left + 2, y + 3, 0xFF777777, true);
                }
                y += ROW_H;
            } else {
                for (int i = 0; i < slots.size(); i++) {
                    WatermarkSlot slot = slots.get(i);
                    boolean en = slot != null && slot.enabled;
                    if (y >= py && y + ROW_H <= py + panelH) {
                        boolean rowHov = mx >= left && mx <= left + innerW && my >= y && my < y + ROW_H;
                        if (rowHov) ctx.fill(left, y, left + innerW, y + ROW_H - 1, 0x12FFFFFF);
                        int eyeX = left + 1;
                        boolean eyeHov = mx >= eyeX && mx <= eyeX + 12 && my >= y && my < y + ROW_H;
                        String eyeIcon = en ? "\u25C9" : "\u25CE";
                        int eyeColor = en ? (eyeHov ? 0xFFAAFFAA : 0xFF66BB66) : (eyeHov ? 0xFFFF8888 : 0xFF884444);
                        ctx.text(tr, Component.literal(eyeIcon), eyeX, y + 3, eyeColor, true);
                        String nm = "\u25A4 " + tr.plainSubstrByWidth((slot != null && slot.name != null ? slot.name : "Watermark"), innerW - 42);
                        ctx.text(tr, Component.literal(nm), left + 13, y + 3, en ? 0xFFCCCCCC : 0xFF777777, true);
                        int ex = left + innerW - 22;
                        boolean ehov = mx >= ex && mx <= ex + 20 && my >= y && my < y + ROW_H;
                        ctx.text(tr, Component.literal("Edit"), ex, y + 3, ehov ? 0xFFFFCC44 : 0xFF8899BB, true);
                    }
                    y += ROW_H;
                }
            }
            if (y >= py && y + ROW_H <= py + panelH) {
                boolean ohov = mx >= left && mx <= left + innerW && my >= y && my < y + ROW_H;
                ctx.text(tr, Component.literal("\u2795 Open Watermark Editor"), left + 4, y + 3, ohov ? 0xFF66CCFF : 0xFF6699CC, true);
            }
            y += ROW_H;
        }
    }

    /** Render a clickable section header with accent color and hover effect. Returns y + ROW_H. */
    private int renderSectionHeader(GuiGraphicsExtractor ctx, Font tr, String openLabel, String closedLabel,
                                     boolean open, int x, int y, int w, int mx, int my, int clipTop, int clipBot) {
        if (y >= clipTop && y + ROW_H <= clipBot) {
            boolean hov = mx >= x && mx <= x + w && my >= y && my < y + ROW_H;
            ctx.fill(x, y, x + w, y + ROW_H - 1, hov ? tSectionHov() : tSectionBg());
            // Left accent bar
            ctx.fill(x, y + 2, x + 2, y + ROW_H - 3, tAccent());
            ctx.text(tr, Component.literal(open ? openLabel : closedLabel), x + 5, y + 4, tAccent(), true);
        }
        return y + ROW_H;
    }

    private void renderOpacityRow(GuiGraphicsExtractor ctx, Font tr, OpacityEntry entry,
                                   int x, int y, int w, int mx, int my) {
        int val = entry.getter.get();
        // Label + value
        String lbl = entry.label + " " + val + "%";
        ctx.text(tr, Component.literal(lbl), x + 2, y + 1, 0xFFAAAAAA, true);

        // Mini bar
        int barX = x + 2, barY = y + 10, barW = w - 4, barH = 3;
        ctx.fill(barX, barY, barX + barW, barY + barH, 0xFF222222);
        int fillW = (int) (barW * val / 100.0);
        ctx.fill(barX, barY, barX + fillW, barY + barH, tAccent());

        // Hover knob
        if (mx >= barX && mx <= barX + barW && my >= y && my <= y + ROW_H - 2) {
            ctx.fill(barX + fillW - 1, barY - 1, barX + fillW + 2, barY + barH + 1, 0xFFFFFFFF);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Overlay preview (scaled space)
    // ═══════════════════════════════════════════════════════════════════════

    /** Preview the Classic single-panel overlay at its (possibly dragged) position. */
    private void renderClassicPreview(GuiGraphicsExtractor context, RecordableConfig config, Font tr) {
        if (!config.hudClassicVisible) return;
        dev.recordable.theme.ThemeColors skin = config.activeOverlaySkinOrNull();
        int accentRgb = skin != null ? (skin.accent & 0xFFFFFF) : config.getOverlayColorRgb();
        int accentArgb = 0xFF000000 | accentRgb;
        int accentSoftArgb = 0xAA000000 | accentRgb;
        String[] lines = { "REC 00:12:34", "60 FPS  drop 0", "Size: 12.3 MB", "1920x1080  Queue: 0/240 (OK)" };
        int maxW = 0; for (String l : lines) maxW = Math.max(maxW, tr.width(l));
        int panelWidth = maxW + 22;
        int panelHeight = 12 + lines.length * 11;
        int[] cp = config.classicPanelPos(vw, vh, panelWidth, panelHeight, 10);
        int x = cp[0], y = cp[1];
        int panelBg = skin != null ? skin.panelBackground : 0x99000000;
        context.fill(x - 3, y - 3, x + panelWidth, y + panelHeight, panelBg);
        context.fill(x - 3, y - 3, x + panelWidth, y - 2, accentSoftArgb);
        context.fill(x, y + 3, x + 8, y + 11, accentArgb);
        int ly = y;
        context.text(tr, Component.literal(lines[0]), x + 13, ly, 0xFFFFFFFF, true); ly += 12;
        context.text(tr, Component.literal(lines[1]), x, ly, skin != null ? skin.textSecondary : 0xFFE0E0E0, true); ly += 11;
        context.text(tr, Component.literal(lines[2]), x, ly, 0xFFFFFFFF, true); ly += 11;
        context.text(tr, Component.literal(lines[3]), x, ly, 0xFF9BE28F, true);
    }

    /** Preview the Synthwave single-panel overlay at its (possibly dragged) position. */
    private void renderSynthwavePreview(GuiGraphicsExtractor context, RecordableConfig config, Font tr) {
        if (!config.hudSynthVisible) return;
        dev.recordable.theme.ThemeColors skin = config.activeOverlaySkinOrNull();
        int magenta = skin != null ? skin.accent : 0xFFFF2D95;
        int cyan = skin != null ? skin.accentHover : 0xFF00E5FF;
        int panelBg = skin != null ? skin.panelBackground : 0xE61A0B2E;
        int textColor = skin != null ? skin.textPrimary : cyan;
        String line = "REC 00:12:34";
        int pw = tr.width(line) + 22, ph = 16;
        int[] sp = config.synthPanelPos(vw, vh, pw, ph, 0, 0);
        int x = sp[0], y = sp[1];
        context.fill(x, y, x + pw, y + ph, panelBg);
        context.fill(x, y, x + pw, y + 1, magenta);
        context.fill(x, y + ph - 1, x + pw, y + ph, cyan);
        context.fill(x, y, x + 1, y + ph, magenta);
        context.fill(x + pw - 1, y, x + pw, y + ph, cyan);
        context.fill(x + 6, y + 5, x + 12, y + 11, magenta);
        context.text(tr, Component.literal(line), x + 16, y + 4, textColor, true);
    }

    private void renderOverlayPreview(GuiGraphicsExtractor context, RecordableConfig config, Font tr) {
        // Live filter preview: draw enabled filters beneath the HUD preview so toggles show instantly.
        if (config.showFiltersLive) {
            for (String layerId : RecordableConfig.FILTER_LAYERS) {
                if (!config.isElementVisible(layerId)) continue;
                dev.recordable.filter.FilterType ft = RecordableConfig.filterLayerToType(layerId);
                dev.recordable.FilterPreviewRenderer.render(context, vw, vh, ft, config.getFilterIntensity(layerId));
            }
        }

        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud != null
                ? config.overlayStyleHud : RecordableConfig.OverlayStyleHud.CLASSIC;
        boolean isVhs = style == RecordableConfig.OverlayStyleHud.VHS;
        // Each style previews only what it actually renders (matches the editor's dynamic
        // element list). Classic and Synthwave draw their single panel; None draws nothing.
        if (style == RecordableConfig.OverlayStyleHud.CLASSIC) { renderClassicPreview(context, config, tr); return; }
        if (style == RecordableConfig.OverlayStyleHud.SYNTHWAVE) { renderSynthwavePreview(context, config, tr); return; }
        if (!isVhs) return; // NONE: nothing to preview

        int playColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsPlayColor, 0xFFFFFFFF), config.hudPlayRecOpacity);
        int recDotColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsRecDotColor, 0xFFCC1E1E), config.hudPlayRecOpacity);
        int recTextColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsRecTextColor, 0xFFFFFFFF), config.hudPlayRecOpacity);
        int timestampColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsTimestampColor, 0xFFFFFFFF), config.hudTimestampOpacity);
        int dateColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsDateColor, 0xFFFFFFFF), config.hudDetailsOpacity);
        int spColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsSpColor, 0xFFFFFFFF), config.hudSpOpacity);
        int bracketColor = RecordableConfig.applyOpacity(RecordableConfig.parseArgbColor(config.vhsBracketColor, 0xC8FFFFFF), config.hudCornersOpacity);

        int leftInset = config.hudPlayRecX, topInset = config.hudPlayRecY;

        // PLAY/REC preview
        if (config.hudPlayRecVisible) {
            if (config.vhsShowPlay)
                context.text(tr, Component.literal("PLAY \u25B6"), leftInset, topInset, playColor, true);

            int recY = topInset + (config.vhsShowPlay ? 12 : 0);
            context.fill(leftInset, recY + 2, leftInset + 7, recY + 9, recDotColor);
            context.text(tr, Component.literal("REC"), leftInset + 10, recY, recTextColor, true);
        }

        // Timestamp preview
        if (config.hudTimestampVisible) {
            String timer = "00:12:34"; int timerW = tr.width(timer);
            context.text(tr, Component.literal(timer), vw - config.hudTimestampOffsetX - timerW, config.hudTimestampY, timestampColor, true);
        }

        // Corners preview
        if (config.hudCornersVisible && config.vhsShowBrackets)
            drawCornerBrackets(context, config.hudCornersX, config.hudCornersY,
                    config.hudCornersX + config.hudCornersWidth, config.hudCornersY + config.hudCornersHeight, 20, 2, bracketColor);

        // SP preview
        if (config.hudSpVisible && config.vhsShowSp)
            context.text(tr, Component.literal("SP"), config.hudSpX, vh - config.hudSpOffsetY, spColor, true);

        // Details preview
        if (config.hudDetailsVisible) {
            int brX = vw - config.hudDetailsOffsetX;
            int detY = vh - config.hudDetailsOffsetY;
            if (config.vhsShowDate) {
                LocalDateTime now = LocalDateTime.now();
                String dl = DATE_FMT.format(now), tl = TIME_FMT.format(now).toUpperCase(Locale.ROOT);
                detY -= 22;
                context.text(tr, Component.literal(tl), brX - tr.width(tl), detY, dateColor, true);
                detY += 11;
                context.text(tr, Component.literal(dl), brX - tr.width(dl), detY, dateColor, true);
                detY = vh - config.hudDetailsOffsetY - 26;
            } else { detY -= 4; }
            if (config.vhsShowTapeCounter) {
                String tc = "TC 0143"; detY -= 11;
                context.text(tr, Component.literal(tc), brX - tr.width(tc), detY,
                        RecordableConfig.applyOpacity(0xFFCCCCCC, config.hudDetailsOpacity));
            }
            if (config.vhsShowAudioMeter) {
                detY -= 12; int mX = brX - 60;
                context.fill(mX, detY, mX + 55, detY + 3, 0xFF333333);
                context.fill(mX, detY + 4, mX + 55, detY + 7, 0xFF333333);
                context.fill(mX, detY, mX + 30, detY + 3, 0xFF44CC44);
                context.fill(mX, detY + 4, mX + 28, detY + 7, 0xFF44CC44);
            }
            if (config.vhsShowBattery) {
                detY -= 13; int bx = brX - 50, bw = 24, bh = 10;
                context.fill(bx, detY, bx + bw, detY + bh, 0xFFAAAAAA);
                context.fill(bx + 1, detY + 1, bx + bw - 1, detY + bh - 1, 0xFF222222);
                context.fill(bx + bw, detY + 2, bx + bw + 2, detY + bh - 2, 0xFFAAAAAA);
                context.fill(bx + 2, detY + 2, bx + 18, detY + bh - 2, 0xFF44CC44);
                context.text(tr, Component.literal("98%"), bx + bw + 5, detY + 1, 0xFFCCCCCC, true);
            }
        }

        // Perf preview
        if (config.hudPerfVisible && config.showPerformanceStats) {
            String[] lines = {"Cap 60 | Enc 60 FPS", "Mem 1024 MiB | Drop 0", "Queue: 0/240 | 100%"};
            int mw = 0; for (String l : lines) mw = Math.max(mw, tr.width(l));
            int pw = mw + 10, ph = 8 + lines.length * 10;
            int ppx = vw - pw - config.hudPerfOffsetX, ppy = vh - ph - config.hudPerfOffsetY;
            int po = config.hudPerfOpacity;
            context.fill(ppx - 2, ppy - 2, ppx + pw, ppy + ph, RecordableConfig.applyOpacity(0x88000000, po));
            int ly = ppy; int[] cs = {0xFFCFCFCF, 0xFFB0B0B0, 0xFF9BE28F};
            for (int i = 0; i < lines.length; i++) {
                context.text(tr, Component.literal(lines[i]), ppx + 2, ly, RecordableConfig.applyOpacity(cs[i], po), true);
                ly += 10;
            }
        }
    }

    private static void drawCornerBrackets(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int len, int t, int c) {
        ctx.fill(x1, y1, x1 + len, y1 + t, c); ctx.fill(x1, y1, x1 + t, y1 + len, c);
        ctx.fill(x2 - len, y1, x2, y1 + t, c); ctx.fill(x2 - t, y1, x2, y1 + len, c);
        ctx.fill(x1, y2 - t, x1 + len, y2, c); ctx.fill(x1, y2 - len, x1 + t, y2, c);
        ctx.fill(x2 - len, y2 - t, x2, y2, c); ctx.fill(x2 - t, y2 - len, x2, y2, c);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mouse handling
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;

        int mx = (int) click.x(), my = (int) click.y(), btn = click.button();


        // Panel click
        if (panelOpen && mx >= this.width - PANEL_W - 10) {
            return handlePanelClick(mx, my, btn);
        }

        // Scaled coords for canvas
        int smx = (int) (mx / overlayScale), smy = (int) (my / overlayScale);

        // Resize handles
        for (DraggableElement el : elements) {
            if (el.resizable) {
                ResizeEdge edge = hitTestResizeHandle(el, smx, smy);
                if (edge != ResizeEdge.NONE && btn == 0) {
                    activeResize = edge; resizeElement = el;
                    resizeOrigX = el.x; resizeOrigY = el.y; resizeOrigW = el.w; resizeOrigH = el.h;
                    dragMouseX = smx; dragMouseY = smy;
                    return true;
                }
            }
        }

        // Element click - reverse layer order (topmost first), skip hidden, Corners click-through
        RecordableConfig cfgClick = RecordableConfig.get();
        DraggableElement clicked = null;
        List<String> shownClick = shownLayers();
        for (int i = shownClick.size() - 1; i >= 0; i--) {
            String lid = shownClick.get(i);
            if (!cfgClick.isElementVisible(lid)) continue;
            for (DraggableElement el : elements) {
                if (!el.id.equals(lid)) continue;
                if (smx >= el.x && smx <= el.x + el.w && smy >= el.y && smy <= el.y + el.h) {
                    if (el.id.equals("Corners")) {
                        int cz = 40;
                        boolean nearTL = smx < el.x + cz && smy < el.y + cz;
                        boolean nearTR = smx > el.x + el.w - cz && smy < el.y + cz;
                        boolean nearBL = smx < el.x + cz && smy > el.y + el.h - cz;
                        boolean nearBR = smx > el.x + el.w - cz && smy > el.y + el.h - cz;
                        if (!(nearTL || nearTR || nearBL || nearBR)) break; // skip Corners interior
                    }
                    clicked = el;
                }
                break;
            }
            if (clicked != null) break;
        }

        // Watermark click (topmost first) - separate pass (not in layerOrder)
        if (clicked == null) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                DraggableElement el = elements.get(i);
                if (el.wmIndex < 0) continue;
                if (smx >= el.x && smx <= el.x + el.w && smy >= el.y && smy <= el.y + el.h) { clicked = el; break; }
            }
        }
        if (clicked == null) return false;
        if (btn == 1) { resetElementToDefault(clicked); return true; }
        if (btn == 0) {
            draggedElement = clicked;
            dragOffsetX = smx - clicked.x; dragOffsetY = smy - clicked.y;
            dragMouseX = smx; dragMouseY = smy;
            return true;
        }
        return false;
    }

    private boolean handlePanelClick(int mx, int my, int btn) {
        RecordableConfig config = RecordableConfig.get();
        int px = this.width - PANEL_W - 4;
        int py = 36;
        int innerW = PANEL_W - 6;
        int left = px + 3;

        int y = py + 2 - panelScroll;

        // ── Layers header ──
        if (my >= y && my < y + ROW_H && mx >= left && mx <= left + innerW && btn == 0) {
            sectionLayersOpen = !sectionLayersOpen; return true;
        }
        y += ROW_H;
        if (sectionLayersOpen) {
            List<String> shown = shownLayers();
            for (int i = 0; i < shown.size(); i++) {
                String id = shown.get(i);
                if (my >= y && my < y + ROW_H && btn == 0) {
                    // Eye toggle zone (left 12px)
                    int eyeX = left + 1;
                    if (mx >= eyeX && mx <= eyeX + 12) {
                        config.setElementVisible(id, !config.isElementVisible(id));
                        config.save(); return true;
                    }
                    // Reorder arrows (right 14px)
                    int ax = left + innerW - 14;
                    if (i > 0 && mx >= ax && mx <= ax + 14 && my < y + 8) {
                        moveShownLayer(shown, i, -1); return true;
                    }
                    if (i < shown.size() - 1 && mx >= ax && mx <= ax + 14 && my >= y + 8) {
                        moveShownLayer(shown, i, 1); return true;
                    }
                }
                y += ROW_H;
            }
        }
        y += 2; // divider

        // ── Opacity header ──
        if (my >= y && my < y + ROW_H && mx >= left && mx <= left + innerW && btn == 0) {
            sectionOpacityOpen = !sectionOpacityOpen; return true;
        }
        y += ROW_H;
        if (sectionOpacityOpen) {
            int barX = left + 2, barW = innerW - 4;
            for (OpacityEntry entry : opacityEntries) {
                if (my >= y && my < y + ROW_H - 1) {
                    if (btn == 0) {
                        int newVal = Math.max(0, Math.min(100, (mx - barX) * 100 / barW));
                        entry.setter.accept(newVal); draggingOpacity = entry;
                        config.save(); return true;
                    }
                    if (btn == 1) { entry.setter.accept(100); config.save(); return true; }
                }
                y += ROW_H - 1;
            }
        }
        y += 2; // divider before watermarks

        // Watermarks section
        if (my >= y && my < y + ROW_H && mx >= left && mx <= left + innerW && btn == 0) {
            sectionWatermarksOpen = !sectionWatermarksOpen; return true;
        }
        y += ROW_H;
        if (sectionWatermarksOpen) {
            java.util.List<WatermarkSlot> slots = config.watermarkSlots;
            if (slots == null || slots.isEmpty()) {
                y += ROW_H;
            } else {
                for (int i = 0; i < slots.size(); i++) {
                    WatermarkSlot slot = slots.get(i);
                    if (my >= y && my < y + ROW_H && btn == 0) {
                        int ex = left + innerW - 22;
                        if (mx >= ex && mx <= ex + 20) { openWatermarkEditor(i); return true; }
                        if (slot != null) { slot.enabled = !slot.enabled; config.save(); }
                        return true;
                    }
                    y += ROW_H;
                }
            }
            if (my >= y && my < y + ROW_H && mx >= left && mx <= left + innerW && btn == 0) {
                openWatermarkEditor(-1); return true;
            }
            y += ROW_H;
        }

        return true; // consume
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        double sdx = deltaX / overlayScale, sdy = deltaY / overlayScale;

        // Opacity slider drag
        if (draggingOpacity != null && click.button() == 0) {
            int px = this.width - PANEL_W - 4;
            int barX = px + 5, barW = PANEL_W - 10;
            int realMx = (int) (click.x() + deltaX);
            int newVal = Math.max(0, Math.min(100, (realMx - barX) * 100 / barW));
            draggingOpacity.setter.accept(newVal);
            return true;
        }

        // Resize
        if (activeResize != ResizeEdge.NONE && resizeElement != null && click.button() == 0) {
            dragMouseX += sdx; dragMouseY += sdy;
            applyResize(resizeElement, (int) dragMouseX, (int) dragMouseY);
            return true;
        }

        // Drag
        if (draggedElement != null && click.button() == 0) {
            dragMouseX += sdx; dragMouseY += sdy;
            int nx = Math.max(0, Math.min(vw - draggedElement.w, (int) dragMouseX - dragOffsetX));
            int ny = Math.max(0, Math.min(vh - draggedElement.h, (int) dragMouseY - dragOffsetY));
            applyDragPosition(draggedElement, nx, ny);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        draggingOpacity = null;
        if (activeResize != ResizeEdge.NONE && click.button() == 0) {
            activeResize = ResizeEdge.NONE; resizeElement = null;
            RecordableConfig.get().save(); return true;
        }
        if (draggedElement != null && click.button() == 0) {
            draggedElement = null; RecordableConfig.get().save(); return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmt, double vAmt) {
        if (panelOpen && mouseX >= this.width - PANEL_W - 10) {
            int delta = (int) Math.round(vAmt * -8.0);
            panelScroll = Math.max(0, panelScroll + delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, hAmt, vAmt);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean charTyped(CharacterEvent charEvent) {
        return super.charTyped(charEvent);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Resize / Position
    // ═══════════════════════════════════════════════════════════════════════

    private void applyResize(DraggableElement el, int mouseX, int mouseY) {
        RecordableConfig config = RecordableConfig.get();
        int minW = 20, minH = 10;
        int nX = resizeOrigX, nY = resizeOrigY, nW = resizeOrigW, nH = resizeOrigH;
        switch (activeResize) {
            case TOP_LEFT -> { int dx = mouseX - resizeOrigX, dy = mouseY - resizeOrigY; nX += dx; nY += dy; nW -= dx; nH -= dy; }
            case TOP_RIGHT -> { int dy = mouseY - resizeOrigY; nY += dy; nW = mouseX - resizeOrigX; nH -= dy; }
            case BOTTOM_LEFT -> { int dx = mouseX - resizeOrigX; nX += dx; nW -= dx; nH = mouseY - resizeOrigY; }
            case BOTTOM_RIGHT -> { nW = mouseX - resizeOrigX; nH = mouseY - resizeOrigY; }
            default -> { return; }
        }
        if (nW < minW) { nW = minW; nX = resizeOrigX + resizeOrigW - minW; }
        if (nH < minH) { nH = minH; nY = resizeOrigY + resizeOrigH - minH; }
        nX = Math.max(0, nX); nY = Math.max(0, nY);
        nW = Math.max(minW, Math.min(vw - nX, nW)); nH = Math.max(minH, Math.min(vh - nY, nH));

        switch (el.id) {
            case "Corners" -> { config.hudCornersX = nX; config.hudCornersY = nY; config.hudCornersWidth = nW; config.hudCornersHeight = nH; }
            case "PLAY/REC" -> { config.hudPlayRecX = nX; config.hudPlayRecY = nY; config.hudPlayRecW = nW; config.hudPlayRecH = nH; }
            case "Timestamp" -> { config.hudTimestampOffsetX = vw - nX - nW; config.hudTimestampY = nY; config.hudTimestampW = nW; config.hudTimestampH = nH; }
            case "SP" -> { config.hudSpX = nX; config.hudSpOffsetY = vh - nY; config.hudSpW = nW; config.hudSpH = nH; }
            case "Details" -> { config.hudDetailsOffsetX = vw - nX - nW; config.hudDetailsOffsetY = vh - nY - nH; config.hudDetailsW = nW; config.hudDetailsH = nH; }
            case "Perf" -> { config.hudPerfOffsetX = vw - nX - nW; config.hudPerfOffsetY = vh - nY - nH; config.hudPerfW = nW; config.hudPerfH = nH; }
        }
    }

    private void applyDragPosition(DraggableElement el, int sx, int sy) {
        RecordableConfig config = RecordableConfig.get();
        if (el.wmIndex >= 0) {
            if (config.watermarkSlots != null && el.wmIndex < config.watermarkSlots.size()) {
                WatermarkSlot slot = config.watermarkSlots.get(el.wmIndex);
                slot.position = WatermarkSlot.Position.CUSTOM;
                slot.customX = sx; slot.customY = sy;
            }
            return;
        }
        switch (el.id) {
            case "PLAY/REC" -> { config.hudPlayRecX = sx; config.hudPlayRecY = sy; }
            case "Timestamp" -> { config.hudTimestampOffsetX = vw - sx - el.w; config.hudTimestampY = sy; }
            case "Corners" -> { config.hudCornersX = sx; config.hudCornersY = sy; }
            case "SP" -> { config.hudSpX = sx; config.hudSpOffsetY = vh - sy; }
            case "Details" -> { config.hudDetailsOffsetX = vw - sx - el.w; config.hudDetailsOffsetY = vh - sy - el.h; }
            case "Perf" -> { config.hudPerfOffsetX = vw - sx - el.w; config.hudPerfOffsetY = vh - sy - el.h; }
            case "Mic" -> { config.hudMicX = sx; config.hudMicY = sy; }
            case "Classic" -> { config.hudClassicX = sx; config.hudClassicY = sy; }
            case "Synthwave" -> { config.hudSynthX = sx; config.hudSynthY = sy; }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reset
    // ═══════════════════════════════════════════════════════════════════════

    private void resetElementToDefault(DraggableElement el) {
        RecordableConfig c = RecordableConfig.get();
        if (el.wmIndex >= 0) {
            if (c.watermarkSlots != null && el.wmIndex < c.watermarkSlots.size()) {
                c.watermarkSlots.get(el.wmIndex).position = WatermarkSlot.Position.BOTTOM_RIGHT;
                c.save();
            }
            return;
        }
        switch (el.id) {
            case "PLAY/REC" -> { c.hudPlayRecX = DEF_PLAY_REC_X; c.hudPlayRecY = DEF_PLAY_REC_Y; c.hudPlayRecW = 0; c.hudPlayRecH = 0; }
            case "Timestamp" -> { c.hudTimestampOffsetX = DEF_TS_OFFSET_X; c.hudTimestampY = DEF_TS_Y; c.hudTimestampW = 0; c.hudTimestampH = 0; }
            case "Corners" -> { c.hudCornersX = DEF_CORNERS_X; c.hudCornersY = DEF_CORNERS_Y; c.hudCornersWidth = DEF_CORNERS_W; c.hudCornersHeight = DEF_CORNERS_H; }
            case "SP" -> { c.hudSpX = DEF_SP_X; c.hudSpOffsetY = DEF_SP_OFFSET_Y; c.hudSpW = 0; c.hudSpH = 0; }
            case "Details" -> { c.hudDetailsOffsetX = DEF_DETAILS_OFFSET_X; c.hudDetailsOffsetY = DEF_DETAILS_OFFSET_Y; c.hudDetailsW = 0; c.hudDetailsH = 0; }
            case "Perf" -> { c.hudPerfOffsetX = DEF_PERF_OFFSET_X; c.hudPerfOffsetY = DEF_PERF_OFFSET_Y; c.hudPerfW = 0; c.hudPerfH = 0; }
            case "Mic" -> { c.hudMicX = -1; c.hudMicY = 4; c.hudMicOpacity = 100; }
            case "Classic" -> { c.hudClassicX = -1; c.hudClassicY = -1; }
            case "Synthwave" -> { c.hudSynthX = -1; c.hudSynthY = -1; }
        }
        c.save();
    }

    private void resetAllDefaults() {
        RecordableConfig c = RecordableConfig.get();
        c.hudPlayRecX = DEF_PLAY_REC_X; c.hudPlayRecY = DEF_PLAY_REC_Y;
        c.hudTimestampOffsetX = DEF_TS_OFFSET_X; c.hudTimestampY = DEF_TS_Y;
        c.hudSpX = DEF_SP_X; c.hudSpOffsetY = DEF_SP_OFFSET_Y;
        c.hudPerfOffsetX = DEF_PERF_OFFSET_X; c.hudPerfOffsetY = DEF_PERF_OFFSET_Y;
        c.hudDetailsOffsetX = DEF_DETAILS_OFFSET_X; c.hudDetailsOffsetY = DEF_DETAILS_OFFSET_Y;
        c.hudCornersX = DEF_CORNERS_X; c.hudCornersY = DEF_CORNERS_Y;
        c.hudCornersWidth = DEF_CORNERS_W; c.hudCornersHeight = DEF_CORNERS_H;
        c.hudPlayRecW = 0; c.hudPlayRecH = 0; c.hudTimestampW = 0; c.hudTimestampH = 0;
        c.hudSpW = 0; c.hudSpH = 0; c.hudPerfW = 0; c.hudPerfH = 0;
        c.hudDetailsW = 0; c.hudDetailsH = 0;
        c.hudPlayRecOpacity = 100; c.hudTimestampOpacity = 100; c.hudCornersOpacity = 100;
        c.hudSpOpacity = 100; c.hudDetailsOpacity = 100; c.hudPerfOpacity = 100;
        c.hudPlayRecVisible = true; c.hudTimestampVisible = true; c.hudCornersVisible = true;
        c.hudSpVisible = true; c.hudDetailsVisible = true; c.hudPerfVisible = true;
        c.hudMicX = -1; c.hudMicY = 4; c.hudMicOpacity = 100; c.hudMicVisible = true;
        layerOrder.clear();
        for (String s : DEF_LAYER_ORDER.split(",")) layerOrder.add(s.trim());
        c.hudLayerOrder = DEF_LAYER_ORDER;
        c.save();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Navigation
    // ═══════════════════════════════════════════════════════════════════════

    @Override public void onClose() { cancelAndClose(); }

    private void cancelAndClose() {
        if (!cancelled) { cancelled = true; restoreAll(RecordableConfig.get()); }
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    private void saveAndClose() {
        RecordableConfig c = RecordableConfig.get();
        c.hudLayerOrder = String.join(",", layerOrder);
        c.sanitize(); c.save();
        cancelled = true;
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
