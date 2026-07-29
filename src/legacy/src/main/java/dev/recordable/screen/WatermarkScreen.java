package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkManager;
import dev.recordable.WatermarkSlot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import dev.recordable.theme.CycleButton;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

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
    private TextFieldWidget textField;
    private TextFieldWidget hexField;
    private String statusMessage = "";

    public WatermarkScreen(Screen parent) {
        super(Text.translatable("screen.recordable.watermark.title"));
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
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        this.clearChildren();
        RecordableConfig config = RecordableConfig.get();
        int topY = this.panelTop + 32;

        // Master enable toggle
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Watermarks: " + (config.watermarksEnabled ? "ON" : "OFF")),
                button -> {
                    config.watermarksEnabled = !config.watermarksEnabled;
                    config.save();
                    rebuildWidgets();
                }).dimensions(this.listLeft, topY, 120, 18).build());

        // Live preview toggle
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Live Preview: " + (config.showWatermarksLive ? "ON" : "OFF")),
                button -> {
                    config.showWatermarksLive = !config.showWatermarksLive;
                    config.save();
                    rebuildWidgets();
                }).dimensions(this.listLeft + 128, topY, 130, 18).build());

        // Add buttons (respect max slots)
        boolean canAdd = slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS;
        ButtonWidget addText = ButtonWidget.builder(Text.literal("+ Text"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        WatermarkSlot s = new WatermarkSlot("Text " + (slots().size() + 1), WatermarkSlot.Kind.TEXT);
                        s.enabled = true;
                        slots().add(s);
                        selectedIndex = slots().size() - 1;
                        config.save();
                        rebuildWidgets();
                    }
                }).dimensions(this.editLeft, topY, (this.editWidth - 16) / 3, 18).build();
        addText.active = canAdd;
        this.addDrawableChild(addText);

        ButtonWidget addImage = ButtonWidget.builder(Text.literal("+ Image"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        WatermarkSlot s = new WatermarkSlot("Image " + (slots().size() + 1), WatermarkSlot.Kind.IMAGE);
                        s.enabled = true;
                        slots().add(s);
                        selectedIndex = slots().size() - 1;
                        config.save();
                        rebuildWidgets();
                    }
                }).dimensions(this.editLeft + (this.editWidth - 16) / 3 + 8, topY, (this.editWidth - 16) / 3, 18).build();
        addImage.active = canAdd;
        this.addDrawableChild(addImage);

        // Presets button (cycles through built-ins)
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Preset: User"),
                button -> {
                    if (slots().size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
                        slots().addAll(WatermarkManager.builtinUsernameStamp());
                        while (slots().size() > RecordableConfig.MAX_WATERMARK_SLOTS) {
                            slots().remove(slots().size() - 1);
                        }
                        config.watermarksEnabled = true;
                        config.save();
                        this.statusMessage = "Applied username preset.";
                        rebuildWidgets();
                    }
                }).dimensions(this.editLeft + 2 * ((this.editWidth - 16) / 3) + 16, topY, (this.editWidth - 16) / 3, 18).build());

        // Slot list buttons (select / toggle / delete)
        List<WatermarkSlot> list = slots();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            WatermarkSlot s = list.get(i);
            int rowY = this.listTop + (i * ROW_HEIGHT);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal((s.enabled ? "[x] " : "[ ] ") + truncate(s.name, 14)),
                    button -> {
                        selectedIndex = idx;
                        rebuildWidgets();
                    }).dimensions(this.listLeft, rowY, this.listWidth - 88, 18).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(s.enabled ? "On" : "Off"),
                    button -> {
                        s.enabled = !s.enabled;
                        RecordableConfig.get().save();
                        rebuildWidgets();
                    }).dimensions(this.listLeft + this.listWidth - 84, rowY, 38, 18).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Del"),
                    button -> {
                        slots().remove(idx);
                        if (selectedIndex >= slots().size()) selectedIndex = slots().size() - 1;
                        RecordableConfig.get().save();
                        rebuildWidgets();
                    }).dimensions(this.listLeft + this.listWidth - 42, rowY, 38, 18).build());
        }

        // Editor panel for the selected slot
        if (selectedIndex >= 0 && selectedIndex < list.size()) {
            buildEditor(list.get(selectedIndex));
        }

        // Back button
        int backW = 120;
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.recordable.watermark.back"),
                button -> close()
        ).dimensions((this.width - backW) / 2, this.panelBottom - 28, backW, 20).build());
    }

    private void buildEditor(WatermarkSlot slot) {
        RecordableConfig config = RecordableConfig.get();
        int y = this.listTop;
        int w = this.editWidth;

        // Kind cycle
        this.addDrawableChild(CycleButton.create(this.editLeft, y, w, 18,
                Text.literal("Type: " + slot.kind),
                button -> {
                    slot.kind = slot.kind == WatermarkSlot.Kind.TEXT ? WatermarkSlot.Kind.IMAGE : WatermarkSlot.Kind.TEXT;
                    config.save();
                    rebuildWidgets();
                }, button -> {
                    slot.kind = slot.kind == WatermarkSlot.Kind.TEXT ? WatermarkSlot.Kind.IMAGE : WatermarkSlot.Kind.TEXT;
                    config.save();
                    rebuildWidgets();
                }));
        y += ROW_HEIGHT;

        // Content row: free-text field for TEXT, native image picker for IMAGE
        if (slot.kind == WatermarkSlot.Kind.TEXT) {
            this.textField = new TextFieldWidget(this.textRenderer, this.editLeft, y, w, 18,
                    Text.literal("Text"));
            this.textField.setMaxLength(256);
            this.textField.setText(slot.text);
            this.textField.setChangedListener(value -> {
                slot.text = value;
                config.save();
            });
            this.addDrawableChild(this.textField);
        } else {
            this.textField = null;
            String fileLabel = (slot.imagePath == null || slot.imagePath.isBlank())
                    ? "Browse\u2026 (no image)"
                    : "Image: " + truncate(slot.imagePath, 22);
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal(fileLabel),
                    button -> openImagePicker(slot)
            ).dimensions(this.editLeft, y, w, 18).build());
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
            this.hexField = new TextFieldWidget(this.textRenderer, this.editLeft, y, w, 18, Text.literal("Hex"));
            this.hexField.setMaxLength(9);
            this.hexField.setText(stops.get(stopIdx));
            this.hexField.setChangedListener(value -> {
                String norm = normalizeHex(value);
                if (norm != null) {
                    stops.set(stopIdx, norm);
                    slot.textColor = stops.get(0);
                    config.save();
                }
            });
            this.addDrawableChild(this.hexField);
            y += ROW_HEIGHT;

            // Stop selector + add/remove gradient stops (1-10 stops).
            int third = (w - 8) / 3;
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Stop " + (stopIdx + 1) + "/" + stops.size()),
                    button -> {
                        gradientStopIndex = (stopIdx + 1) % stops.size();
                        rebuildWidgets();
                    }).dimensions(this.editLeft, y, third, 18).build());

            ButtonWidget addColor = ButtonWidget.builder(Text.literal("+ Color"),
                    button -> {
                        if (stops.size() < 10) {
                            stops.add(stops.get(stops.size() - 1));
                            gradientStopIndex = stops.size() - 1;
                            slot.textColor = stops.get(0);
                            config.save();
                            rebuildWidgets();
                        }
                    }).dimensions(this.editLeft + third + 4, y, third, 18).build();
            addColor.active = stops.size() < 10;
            this.addDrawableChild(addColor);

            ButtonWidget delColor = ButtonWidget.builder(Text.literal("- Color"),
                    button -> {
                        if (stops.size() > 1) {
                            stops.remove(stopIdx);
                            if (gradientStopIndex >= stops.size()) gradientStopIndex = stops.size() - 1;
                            slot.textColor = stops.get(0);
                            config.save();
                            rebuildWidgets();
                        }
                    }).dimensions(this.editLeft + 2 * (third + 4), y, third, 18).build();
            delColor.active = stops.size() > 1;
            this.addDrawableChild(delColor);
            y += ROW_HEIGHT;
        }

        // Position cycle
        this.addDrawableChild(CycleButton.create(this.editLeft, y, w, 18,
                Text.literal("Pos: " + slot.position),
                button -> {
                    WatermarkSlot.Position[] vals = WatermarkSlot.Position.values();
                    slot.position = vals[(slot.position.ordinal() + 1) % vals.length];
                    config.save();
                    rebuildWidgets();
                }, button -> {
                    WatermarkSlot.Position[] vals = WatermarkSlot.Position.values();
                    slot.position = vals[(slot.position.ordinal() - 1 + vals.length) % vals.length];
                    config.save();
                    rebuildWidgets();
                }));
        y += ROW_HEIGHT;

        // Animation cycle
        this.addDrawableChild(CycleButton.create(this.editLeft, y, w, 18,
                Text.literal("Anim: " + slot.animation),
                button -> {
                    WatermarkSlot.Animation[] vals = WatermarkSlot.Animation.values();
                    slot.animation = vals[(slot.animation.ordinal() + 1) % vals.length];
                    config.save();
                    rebuildWidgets();
                }, button -> {
                    WatermarkSlot.Animation[] vals = WatermarkSlot.Animation.values();
                    slot.animation = vals[(slot.animation.ordinal() - 1 + vals.length) % vals.length];
                    config.save();
                    rebuildWidgets();
                }));
        y += ROW_HEIGHT;

        // Opacity slider
        this.addDrawableChild(new IntSlider(this.editLeft, y, w, 18, "Opacity", slot.opacity, 0, 100, v -> {
            slot.opacity = v;
            config.save();
        }));
        y += ROW_HEIGHT;

        // Scale slider
        this.addDrawableChild(new IntSlider(this.editLeft, y, w, 18, "Scale", slot.scale, 10, 400, v -> {
            slot.scale = v;
            config.save();
        }));
        y += ROW_HEIGHT;

        // Rotation slider
        this.addDrawableChild(new IntSlider(this.editLeft, y, w, 18, "Rotation", slot.rotation, -180, 180, v -> {
            slot.rotation = v;
            config.save();
        }));
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

        RenderHelper.drawText(context, this.textRenderer, Text.literal("§lSlots (" + slots().size()
                + "/" + RecordableConfig.MAX_WATERMARK_SLOTS + ")"), this.listLeft, this.listTop - 12, HEADER_COLOR);
        if (selectedIndex >= 0 && selectedIndex < slots().size()) {
            RenderHelper.drawText(context, this.textRenderer, Text.literal("§lEdit: "
                    + truncate(slots().get(selectedIndex).name, 18)), this.editLeft, this.listTop - 12, HEADER_COLOR);
        } else {
            RenderHelper.drawText(context, this.textRenderer, Text.literal("Select a slot to edit"),
                    this.editLeft, this.listTop - 12, TEXT_COLOR);
        }

        if (slots().isEmpty()) {
            RenderHelper.drawText(context, this.textRenderer, Text.literal("No watermarks. Use + Text / + Image."),
                    this.listLeft, this.listTop + 4, TEXT_COLOR);
        }

        if (!RecordableConfig.get().watermarksEnabled) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Watermarks are OFF - enable to render on recordings."),
                    this.width / 2, this.panelBottom - 44, WARNING_COLOR);
        } else if (!statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(statusMessage),
                    this.width / 2, this.panelBottom - 44, HIGHLIGHT_COLOR);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

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

    /** Parse stored text color (#RRGGBB or #AARRGGBB) into [r,g,b] 0-255. */
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
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
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
                rebuildWidgets();
            });
        }, "recordable-watermark-picker");
        t.setDaemon(true);
        t.start();
    }

    /** Simple integer slider with a fixed range and label. */
    private static final class IntSlider extends SliderWidget {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer setter;

        private IntSlider(int x, int y, int width, int height, String label, int current,
                          int min, int max, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), (current - min) / (double) (max - min));
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
            this.setMessage(Text.literal(label + ": " + current()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(current());
            updateMessage();
        }
    }
}
