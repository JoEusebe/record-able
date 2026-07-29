package dev.recordable.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Lightweight hex color picker with a live preview swatch and inline label. */
public final class ColorPickerWidget extends AbstractWidget {
    public static final int WIDGET_HEIGHT = 20;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int INPUT_WIDTH = 80;
    private static final int INNER_GAP = 5;

    private final Font font;
    private final EditBox textField;
    private final Consumer<String> onColorChanged;
    private final Component label;

    public ColorPickerWidget(Font font,
                             int x,
                             int y,
                             int width,
                             int height,
                             Component label,
                             String currentValue,
                             Consumer<String> onColorChanged) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.onColorChanged = onColorChanged;
        this.label = label == null ? Component.empty() : label;

        this.textField = new EditBox(font, x, y, INPUT_WIDTH, Math.max(16, height - 2), Component.literal("#RRGGBB"));
        this.textField.setMaxLength(7);
        this.textField.setValue(sanitizeColor(currentValue));
        this.textField.setResponder(value -> {
            String candidate = normalize(value);
            if (isValidHex(candidate) && this.onColorChanged != null) {
                this.onColorChanged.accept(candidate.toUpperCase(Locale.ROOT));
            }
        });

        updateTextFieldLayout(x, y);
    }

    public String getColor() {
        String candidate = normalize(this.textField.getValue());
        return isValidHex(candidate) ? candidate.toUpperCase(Locale.ROOT) : "#FF0000";
    }

    public void setPosition(int x, int y) {
        this.setX(x);
        this.setY(y);
        updateTextFieldLayout(x, y);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int previewColor = parseColor(getColor());

        context.fill(x, y, x + this.width, y + this.height, 0xAA151515);
        context.fill(x, y, x + this.width, y + 1, 0xFF424242);
        context.fill(x, y + this.height - 1, x + this.width, y + this.height, 0xFF424242);
        context.fill(x, y, x + 1, y + this.height, 0xFF424242);
        context.fill(x + this.width - 1, y, x + this.width, y + this.height, 0xFF424242);

        int labelAreaWidth = getLabelAreaWidth();
        Component renderLabel = getRenderLabel(labelAreaWidth);
        context.text(this.font, renderLabel, x + 2, y + Math.max(0, (this.height - 8) / 2), 0xFFFFFFFF, true);

        int previewSize = Math.max(12, this.height - 4);
        int previewX = x + labelAreaWidth + INNER_GAP;
        int previewY = y + 2;
        context.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, 0xFF000000 | previewColor);
        context.fill(previewX, previewY, previewX + previewSize, previewY + 1, 0xFFFFFFFF);
        context.fill(previewX, previewY + previewSize - 1, previewX + previewSize, previewY + previewSize, 0xFFFFFFFF);

        this.textField.extractRenderState(context, mouseX, mouseY, delta);

        if (!isValidHex(normalize(this.textField.getValue()))) {
            context.text(this.font, Component.literal("!"), x + this.width - 10, y + (this.height / 2) - 4, 0xFFFF6666, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        boolean handled = this.textField.mouseClicked(click, doubleClick);
        this.setFocused(this.textField.isFocused());
        return handled || super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return this.textField.mouseReleased(click) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return this.textField.mouseDragged(click, deltaX, deltaY) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        return this.textField.keyPressed(keyEvent) || super.keyPressed(keyEvent);
    }

    @Override
    public boolean charTyped(CharacterEvent charEvent) {
        return this.textField.charTyped(charEvent) || super.charTyped(charEvent);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        this.textField.setFocused(focused);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }

    private void updateTextFieldLayout(int x, int y) {
        int previewSize = Math.max(12, this.height - 4);
        int labelAreaWidth = getLabelAreaWidth();
        int inputX = x + labelAreaWidth + INNER_GAP + previewSize + INNER_GAP;
        int maxInputWidth = Math.max(56, this.width - (inputX - x) - INNER_GAP);

        this.textField.setX(inputX);
        this.textField.setY(y + Math.max(0, (this.height - 18) / 2));
        this.textField.setWidth(Math.min(INPUT_WIDTH, maxInputWidth));
    }

    private int getLabelAreaWidth() {
        int previewSize = Math.max(12, this.height - 4);
        int available = Math.max(30, this.width - (INPUT_WIDTH + previewSize + INNER_GAP * 3));
        return available;
    }

    private Component getRenderLabel(int labelAreaWidth) {
        String raw = this.label.getString();
        if (raw == null || raw.isBlank()) {
            return Component.empty();
        }

        String text = raw;
        if (this.font.width(text) > labelAreaWidth) {
            int trimmedWidth = Math.max(10, labelAreaWidth - this.font.width("…"));
            text = this.font.plainSubstrByWidth(text, trimmedWidth) + "…";
        }
        return Component.literal(text);
    }

    private static boolean isValidHex(String value) {
        return HEX_COLOR.matcher(value).matches();
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        return normalized;
    }

    private static String sanitizeColor(String value) {
        String normalized = normalize(value);
        if (!isValidHex(normalized)) {
            return "#FF0000";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.substring(1), 16);
        } catch (Throwable ignored) {
            return 0xFF0000;
        }
    }
}
