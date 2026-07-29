package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.CensorRegion;
import dev.recordable.theme.ThemedButton;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * V1-0.08 full-screen live censor editor (modern variant).
 *
 * <p>Opens directly over the running game (no dimmed background) so the player
 * can add, remove, move and stretch their censor bars in their real on-screen
 * positions while playing. The whole window is the canvas: a region at
 * {@code (0.5, 0.5)} sits at the centre of the screen, exactly where it appears
 * in the live overlay / recording.</p>
 *
 * <ul>
 *   <li>Click an empty spot and drag to create a new bar.</li>
 *   <li>Click a bar to select it, then drag to move it.</li>
 *   <li>Drag the orange handle at a selected bar's bottom-right corner to stretch it.</li>
 *   <li>Press Delete/Backspace (or the Remove button) to delete the selected bar.</li>
 * </ul>
 */
public final class CensorOverlayEditorScreen extends Screen {

    private static final int WIDGET_HEIGHT = 20;
    private static final double MIN_SIZE   = 0.03;
    private static final int HANDLE        = 7;

    private final Screen parent;

    private int selected = -1;
    private int dragMode = 0; // 0 none, 1 move, 2 resize, 3 create
    private double pressFx, pressFy;
    private double origX, origY;

    public CensorOverlayEditorScreen(Screen parent) {
        super(Component.literal("Censor Editor"));
        this.parent = parent;
    }

    private List<CensorRegion> regions() {
        return RecordableConfig.get().censorRegions;
    }

    @Override
    protected void init() {
        super.init();
        int bw = 96;
        int gap = 6;
        int total = bw * 4 + gap * 3;
        int x = (this.width - total) / 2;
        int y = this.height - 28;

        addRenderableWidget(ThemedButton.create(x, y, bw, WIDGET_HEIGHT,
                Component.literal("Add Bar"), b -> addRegion()));
        x += bw + gap;
        addRenderableWidget(ThemedButton.create(x, y, bw, WIDGET_HEIGHT,
                Component.literal("Remove"), b -> removeSelected()));
        x += bw + gap;
        addRenderableWidget(ThemedButton.create(x, y, bw, WIDGET_HEIGHT,
                Component.literal("Clear All"), b -> {
            regions().clear(); selected = -1; RecordableConfig.get().save();
        }));
        x += bw + gap;
        addRenderableWidget(ThemedButton.create(x, y, bw, WIDGET_HEIGHT,
                Component.literal("Done"), b -> onClose()));
    }

    // --- Region helpers ---------------------------------------------------

    private void addRegion() {
        RecordableConfig config = RecordableConfig.get();
        CensorRegion r = new CensorRegion(0.40, 0.45, 0.20, 0.08,
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

    private static CensorRegion.Style parseStyle(String s) {
        if (s != null) {
            try { return CensorRegion.Style.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        return CensorRegion.Style.SOLID;
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
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (dragMode != 0) { canvasDrag((int) click.x(), (int) click.y()); return true; }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragMode != 0) { canvasRelease(); return true; }
        return super.mouseReleased(click);
    }

    private boolean canvasPress(int mx, int my, int button) {
        if (button != 0) return false;
        double fx = mx / (double) this.width;
        double fy = my / (double) this.height;
        List<CensorRegion> rs = regions();

        // Resize handle of the selected region takes priority.
        if (selected >= 0 && selected < rs.size()) {
            CensorRegion r = rs.get(selected);
            int hx = (int) ((r.x + r.width) * this.width);
            int hy = (int) ((r.y + r.height) * this.height);
            if (Math.abs(mx - hx) <= HANDLE && Math.abs(my - hy) <= HANDLE) {
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
                return true;
            }
        }

        // Empty space: create a new region anchored here.
        RecordableConfig config = RecordableConfig.get();
        CensorRegion nr = new CensorRegion(clamp(fx, 0, 1 - MIN_SIZE), clamp(fy, 0, 1 - MIN_SIZE),
                MIN_SIZE, MIN_SIZE, parseStyle(config.streamerDefaultCensorStyle), "Censor");
        rs.add(nr);
        selected = rs.size() - 1;
        dragMode = 3; pressFx = nr.x; pressFy = nr.y;
        return true;
    }

    private void canvasDrag(int mx, int my) {
        if (selected < 0 || selected >= regions().size()) return;
        CensorRegion r = regions().get(selected);
        double fx = clamp(mx / (double) this.width, 0, 1);
        double fy = clamp(my / (double) this.height, 0, 1);
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

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        int keyCode = keyEvent.key();
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            removeSelected();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    // --- Render -----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // No dimmed/blurred backdrop so the live game shows through.
        Font tr = this.font;
        RecordableConfig config = RecordableConfig.get();
        if (config != null) {
            List<CensorRegion> rs = regions();
            for (int i = 0; i < rs.size(); i++) {
                CensorRegion r = rs.get(i);
                int x0 = (int) (r.x * this.width);
                int y0 = (int) (r.y * this.height);
                int x1 = (int) ((r.x + r.width) * this.width);
                int y1 = (int) ((r.y + r.height) * this.height);

                // Semi-transparent so the player can see what is behind while editing.
                context.fill(x0, y0, x1, y1, 0xCC000000 | (r.color & 0xFFFFFF));

                int bc = (i == selected) ? 0xFF44FF44 : 0xFFFFFFFF;
                context.fill(x0, y0, x1, y0 + 1, bc);
                context.fill(x0, y1 - 1, x1, y1, bc);
                context.fill(x0, y0, x0 + 1, y1, bc);
                context.fill(x1 - 1, y0, x1, y1, bc);

                String tag = (r.showLabel && r.label != null && !r.label.isBlank())
                        ? r.label : r.style.name();
                context.text(tr, Component.literal("\u25CF " + tag), x0 + 3, y0 + 3, 0xFFFFFFFF, true);

                if (i == selected) {
                    context.fill(x1 - HANDLE, y1 - HANDLE, x1, y1, 0xFFFF8844);
                }
            }

            String info = "Drag to move  -  drag the orange corner to stretch  -  "
                    + "click empty space to add  -  Delete removes selected";
            context.centeredText(tr, Component.literal(info), this.width / 2, 8, 0xFFFFFFFF);
            String count = rs.isEmpty() ? "No censor bars yet" : ("Bars: " + rs.size());
            context.centeredText(tr, Component.literal(count), this.width / 2, 20, 0xFFB0B0B0);
            if (!config.streamerModeEnabled) {
                context.centeredText(tr,
                        Component.literal("Streamer Mode is OFF - bars are saved but not shown in-game"),
                        this.width / 2, 32, 0xFFFFAA55);
            }
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
