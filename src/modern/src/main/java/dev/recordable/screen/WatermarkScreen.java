package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkManager;
import dev.recordable.WatermarkSlot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import dev.recordable.theme.CycleButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Watermark / Branding editor screen (V1-0.06 Feature 4).
 * Manages up to {@link RecordableConfig#MAX_WATERMARK_SLOTS} watermark slots,
 * each of which can be image- or text-based with independent position,
 * opacity, scale and visibility controls. Supports built-in presets.
 */
public final class WatermarkScreen extends Screen {
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
    private int listLeft;
    private int listWidth;
    private int editLeft;
    private int editWidth;

    private int selectedIndex = -1;
    private int gradientStopIndex = 0;
    private EditBox textField;
    private EditBox hexField;
    private String statusMessage = "";

    public WatermarkScreen(Screen parent) {
        super(Component.translatable("screen.recordable.watermark.title"));
        this.parent = parent;
    }

    private List<WatermarkSlot> slots() {
        return RecordableConfig.get().watermarkSlots;
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.max(420, Math.min((int) (this.width * 0.9D), 720));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.05D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(340, (int) (this.height * 0.88D)));
        this.listLeft = this.panelLeft + 12;
        this.listWidth = (this.panelWidth - 36) / 2;
        this.editLeft = this.listLeft + this.listWidth + 12;
        this.editWidth = this.panelWidth - 24 - this.listWidth - 12;
        this.listTop = this.panelTop + 78; // clear the toggle/add button row (ends ~panelTop+50) so the "Slots"/"Edit" headers don't overlap

        if (selectedIndex >= slots().size()) selectedIndex = slots().size() - 1;
        rebuildPanelWidgets();
    }

    private void rebuildPanelWidgets() {
        this.clearWidgets();
        RecordableConfig config = RecordableConfig.get();
        int topY = this.panelTop + 32;

        this.addRenderableWidget(Button.builder(
                Component.literal("Watermarks: " + (config.watermarksEnabled ? "ON" : "OFF")),
                button -> {
                    config.watermarksEnabled = !config.watermarksEnabled;
                    config.save();
                    rebuildPanelWidgets();
                }).bounds(this.listLeft, topY, 120, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Live Preview: " + (config.showWatermarksLive ? "ON" : "OFF")),
                button -> {
                    config.showWatermarksLive = !config.showWatermarksLive;
                    config.save();
                    rebuildPanelWidgets();
                }).bounds(this.listLeft + 128, topY, 130, 18).build());

        boolean canAdd = slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS;
        Button addText = Button.builder(Component.literal("+ Text"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        WatermarkSlot s = new WatermarkSlot("Text " + (slots().size() + 1), WatermarkSlot.Kind.TEXT);
                        s.enabled = true;
                        slots().add(s);
                        selectedIndex = slots().size() - 1;
                        config.save();
                        rebuildPanelWidgets();
                    }
                }).bounds(this.editLeft, topY, (this.editWidth - 16) / 3, 18).build();
        addText.active = canAdd;
        this.addRenderableWidget(addText);

        Button addImage = Button.builder(Component.literal("+ Image"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        WatermarkSlot s = new WatermarkSlot("Image " + (slots().size() + 1), WatermarkSlot.Kind.IMAGE);
                        s.enabled = true;
                        slots().add(s);
                        selectedIndex = slots().size() - 1;
                        config.save();
                        rebuildPanelWidgets();
                    }
                }).bounds(this.editLeft + (this.editWidth - 16) / 3 + 8, topY, (this.editWidth - 16) / 3, 18).build();
        addImage.active = canAdd;
        this.addRenderableWidget(addImage);

        this.addRenderableWidget(Button.builder(Component.literal("Preset: User"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        slots().addAll(WatermarkManager.builtinUsernameStamp());
                        while (slots().size() > RecordableConfig.MAX_WATERMARK_SLOTS) {
                            slots().remove(slots().size() - 1);
                        }
                        config.watermarksEnabled = true;
                        config.save();
                        this.statusMessage = "Applied username preset.";
                        rebuildPanelWidgets();
                    }
                }).bounds(this.editLeft + 2 * ((this.editWidth - 16) / 3) + 16, topY, (this.editWidth - 16) / 3, 18).build());

        List<WatermarkSlot> list = slots();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            WatermarkSlot s = list.get(i);
            int rowY = this.listTop + (i * ROW_HEIGHT);

            this.addRenderableWidget(Button.builder(
                    Component.literal((s.enabled ? "[x] " : "[ ] ") + truncate(s.name, 14)),
                    button -> {
                        selectedIndex = idx;
                        rebuildPanelWidgets();
                    }).bounds(this.listLeft, rowY, this.listWidth - 88, 18).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal(s.enabled ? "On" : "Off"),
                    button -> {
                        s.enabled = !s.enabled;
                        RecordableConfig.get().save();
                        rebuildPanelWidgets();
                    }).bounds(this.listLeft + this.listWidth - 84, rowY, 38, 18).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("Del"),
                    button -> {
                        slots().remove(idx);
                        if (selectedIndex >= slots().size()) selectedIndex = slots().size() - 1;
                        RecordableConfig.get().save();
                        rebuildPanelWidgets();
                    }).bounds(this.listLeft + this.listWidth - 42, rowY, 38, 18).build());
        }

        if (selectedIndex >= 0 && selectedIndex < list.size()) {
            buildEditor(list.get(selectedIndex));
        }

        int backW = 120;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.recordable.watermark.back"),
                button -> onClose()
        ).bounds((this.width - backW) / 2, this.panelBottom - 28, backW, 20).build());
    }

    private void buildEditor(WatermarkSlot slot) {
        RecordableConfig config = RecordableConfig.get();
        int y = this.listTop;
        int w = this.editWidth;

        this.addRenderableWidget(CycleButton.create(this.editLeft, y, w, 18,
                Component.literal("Type: " + slot.kind),
                button -> {
                    slot.kind = slot.kind == WatermarkSlot.Kind.TEXT ? WatermarkSlot.Kind.IMAGE : WatermarkSlot.Kind.TEXT;
                    config.save();
                    rebuildPanelWidgets();
                }, button -> {
                    slot.kind = slot.kind == WatermarkSlot.Kind.TEXT ? WatermarkSlot.Kind.IMAGE : WatermarkSlot.Kind.TEXT;
                    config.save();
                    rebuildPanelWidgets();
                }));
        y += ROW_HEIGHT;

        // Content row: free-text field for TEXT, native image picker for IMAGE
        if (slot.kind == WatermarkSlot.Kind.TEXT) {
            this.textField = new EditBox(this.font, this.editLeft, y, w, 18, Component.literal("Text"));
            this.textField.setMaxLength(256);
            this.textField.setValue(slot.text);
            this.textField.setResponder(value -> {
                slot.text = value;
                config.save();
            });
            this.addRenderableWidget(this.textField);
        } else {
            this.textField = null;
            String fileLabel = (slot.imagePath == null || slot.imagePath.isBlank())
                    ? "Browse\u2026 (no image)"
                    : "Image: " + truncate(slot.imagePath, 22);
            this.addRenderableWidget(Button.builder(
                    Component.literal(fileLabel),
                    button -> openImagePicker(slot)
            ).bounds(this.editLeft, y, w, 18).build());
        }
        y += ROW_HEIGHT;

        // Color row (TEXT only): hex input for the selected gradient stop + add/remove controls.
        if (slot.kind == WatermarkSlot.Kind.TEXT) {
            java.util.List<String> colors = slot.textColors;
            if (colors == null || colors.isEmpty()) {
                colors = new java.util.ArrayList<>();
                colors.add(slot.textColor == null || slot.textColor.isBlank() ? "#FFFFFFFF" : slot.textColor);
                slot.textColors = colors;
            }
            if (gradientStopIndex >= colors.size()) gradientStopIndex = colors.size() - 1;
            if (gradientStopIndex < 0) gradientStopIndex = 0;
            final java.util.List<String> stops = colors;
            final int stopIdx = gradientStopIndex;

            // Hex text field for the currently selected color stop.
            this.hexField = new EditBox(this.font, this.editLeft, y, w, 18, Component.literal("Hex"));
            this.hexField.setMaxLength(9);
            this.hexField.setValue(stops.get(stopIdx));
            this.hexField.setResponder(value -> {
                String norm = normalizeHex(value);
                if (norm != null) {
                    stops.set(stopIdx, norm);
                    slot.textColor = stops.get(0);
                    config.save();
                }
            });
            this.addRenderableWidget(this.hexField);
            y += ROW_HEIGHT;

            // Stop selector + add/remove gradient stops (1-10 stops).
            int third = (w - 8) / 3;
            this.addRenderableWidget(Button.builder(
                    Component.literal("Stop " + (stopIdx + 1) + "/" + stops.size()),
                    button -> {
                        gradientStopIndex = (stopIdx + 1) % stops.size();
                        rebuildPanelWidgets();
                    }).bounds(this.editLeft, y, third, 18).build());

            Button addColor = Button.builder(Component.literal("+ Color"),
                    button -> {
                        if (stops.size() < 10) {
                            stops.add(stops.get(stops.size() - 1));
                            gradientStopIndex = stops.size() - 1;
                            slot.textColor = stops.get(0);
                            config.save();
                            rebuildPanelWidgets();
                        }
                    }).bounds(this.editLeft + third + 4, y, third, 18).build();
            addColor.active = stops.size() < 10;
            this.addRenderableWidget(addColor);

            Button delColor = Button.builder(Component.literal("- Color"),
                    button -> {
                        if (stops.size() > 1) {
                            stops.remove(stopIdx);
                            if (gradientStopIndex >= stops.size()) gradientStopIndex = stops.size() - 1;
                            slot.textColor = stops.get(0);
                            config.save();
                            rebuildPanelWidgets();
                        }
                    }).bounds(this.editLeft + 2 * (third + 4), y, third, 18).build();
            delColor.active = stops.size() > 1;
            this.addRenderableWidget(delColor);
            y += ROW_HEIGHT;
        }

        this.addRenderableWidget(CycleButton.create(this.editLeft, y, w, 18,
                Component.literal("Pos: " + slot.position),
                button -> {
                    WatermarkSlot.Position[] vals = WatermarkSlot.Position.values();
                    slot.position = vals[(slot.position.ordinal() + 1) % vals.length];
                    config.save();
                    rebuildPanelWidgets();
                }, button -> {
                    WatermarkSlot.Position[] vals = WatermarkSlot.Position.values();
                    slot.position = vals[(slot.position.ordinal() - 1 + vals.length) % vals.length];
                    config.save();
                    rebuildPanelWidgets();
                }));
        y += ROW_HEIGHT;

        this.addRenderableWidget(CycleButton.create(this.editLeft, y, w, 18,
                Component.literal("Anim: " + slot.animation),
                button -> {
                    WatermarkSlot.Animation[] vals = WatermarkSlot.Animation.values();
                    slot.animation = vals[(slot.animation.ordinal() + 1) % vals.length];
                    config.save();
                    rebuildPanelWidgets();
                }, button -> {
                    WatermarkSlot.Animation[] vals = WatermarkSlot.Animation.values();
                    slot.animation = vals[(slot.animation.ordinal() - 1 + vals.length) % vals.length];
                    config.save();
                    rebuildPanelWidgets();
                }));
        y += ROW_HEIGHT;

        this.addRenderableWidget(new IntSlider(this.editLeft, y, w, 18, "Opacity", slot.opacity, 0, 100, v -> {
            slot.opacity = v;
            config.save();
        }));
        y += ROW_HEIGHT;

        this.addRenderableWidget(new IntSlider(this.editLeft, y, w, 18, "Scale", slot.scale, 10, 400, v -> {
            slot.scale = v;
            config.save();
        }));
        y += ROW_HEIGHT;

        this.addRenderableWidget(new IntSlider(this.editLeft, y, w, 18, "Rotation", slot.rotation, -180, 180, v -> {
            slot.rotation = v;
            config.save();
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.extractMenuBackground(context);

        int accent = 0xFF000000 | RecordableConfig.get().getMenuAccentColorRgb();
        int left = this.panelLeft - 6;
        int right = this.panelLeft + this.panelWidth + 6;
        context.fill(left, this.panelTop - 6, right, this.panelBottom, PANEL_COLOR);
        context.fill(left, this.panelTop - 6, right, this.panelTop - 5, accent);
        context.fill(left, this.panelBottom - 1, right, this.panelBottom, PANEL_BORDER_COLOR);
        context.fill(left, this.panelTop - 6, left + 1, this.panelBottom, PANEL_BORDER_COLOR);
        context.fill(right - 1, this.panelTop - 6, right, this.panelBottom, PANEL_BORDER_COLOR);

        context.centeredText(this.font, this.title, this.width / 2, this.panelTop, 0xFFFFFFFF);

        context.text(this.font, Component.literal("§lSlots (" + slots().size()
                + "/" + RecordableConfig.MAX_WATERMARK_SLOTS + ")"), this.listLeft, this.listTop - 12, HEADER_COLOR, true);
        if (selectedIndex >= 0 && selectedIndex < slots().size()) {
            context.text(this.font, Component.literal("§lEdit: "
                    + truncate(slots().get(selectedIndex).name, 18)), this.editLeft, this.listTop - 12, HEADER_COLOR, true);
        } else {
            context.text(this.font, Component.literal("Select a slot to edit"),
                    this.editLeft, this.listTop - 12, TEXT_COLOR, true);
        }

        if (slots().isEmpty()) {
            context.text(this.font, Component.literal("No watermarks. Use + Text / + Image."),
                    this.listLeft, this.listTop + 4, TEXT_COLOR, true);
        }

        if (!RecordableConfig.get().watermarksEnabled) {
            context.centeredText(this.font,
                    Component.literal("Watermarks are OFF - enable to render on recordings."),
                    this.width / 2, this.panelBottom - 44, WARNING_COLOR);
        } else if (!statusMessage.isEmpty()) {
            context.centeredText(this.font, Component.literal(statusMessage),
                    this.width / 2, this.panelBottom - 44, HIGHLIGHT_COLOR);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    /** Parse stored text color (#RRGGBB or #AARRGGBB) into [r,g,b] 0-255. */
    /** Normalize a user-typed hex color to "#RRGGBB"/"#AARRGGBB"; returns null if invalid. */
    private static String normalizeHex(String in) {
        if (in == null) return null;
        String t = in.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6 && t.length() != 8) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return null;
        }
        return "#" + t.toUpperCase(java.util.Locale.ROOT);
    }

    private static int[] parseRgb(String hex) {
        int argb = parseColorArgb(hex);
        return new int[]{ (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF };
    }

    private static int parseColorArgb(String hex) {
        if (hex == null) return 0xFFFFFFFF;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return 0xFF000000 | (int) (Long.parseLong(s, 16) & 0xFFFFFF);
            if (s.length() == 8) return (int) (Long.parseLong(s, 16) & 0xFFFFFFFFL);
        } catch (NumberFormatException ignored) {
        }
        return 0xFFFFFFFF;
    }

    /** Update one RGB channel of the slot's text color and re-store as #RRGGBB. */
    private void setColorComponent(WatermarkSlot slot, int index, int value) {
        int[] rgb = parseRgb(slot.textColor);
        rgb[index] = Math.max(0, Math.min(255, value));
        slot.textColor = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
    }

    /** Open the OS-native file picker off-thread and import the chosen image. */
    private void openImagePicker(WatermarkSlot slot) {
        Thread t = new Thread(() -> {
            String chosen = null;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer filters = stack.mallocPointer(3);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.jpg"));
                filters.put(stack.UTF8("*.jpeg"));
                filters.flip();
                chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                        "Select watermark image", "", filters, "Images (*.png, *.jpg, *.jpeg)", false);
            } catch (Throwable th) {
                dev.recordable.RecordableMod.LOGGER.warn("[Record-able] File picker failed: {}", th.toString());
            }
            final String result = chosen;
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (result != null && !result.isBlank()) {
                    String stored = dev.recordable.WatermarkImageStore.importImage(result);
                    if (stored != null) {
                        slot.imagePath = stored;
                        RecordableConfig.get().save();
                        this.statusMessage = "Imported image: " + stored;
                    } else {
                        this.statusMessage = "Could not import that image.";
                    }
                }
                rebuildPanelWidgets();
            });
        }, "recordable-watermark-picker");
        t.setDaemon(true);
        t.start();
    }

    /** Simple integer slider with a fixed range and label. */
    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer setter;

        private IntSlider(int x, int y, int width, int height, String label, int current,
                          int min, int max, IntConsumer setter) {
            super(x, y, width, height, Component.empty(), (current - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int current() {
            return (int) Math.round(this.value * (max - min)) + min;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + current()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(current());
            updateMessage();
        }
    }
}
