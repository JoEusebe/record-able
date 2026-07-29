package dev.recordable.screen;

import dev.recordable.compat.RenderHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Lightweight hex color picker with a live preview swatch and inline label. */
public final class ColorPickerWidget extends ClickableWidget {
    public static final int WIDGET_HEIGHT = 20;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int INPUT_WIDTH = 80;
    private static final int INNER_GAP = 5;

    private final TextRenderer textRenderer;
    private final TextFieldWidget textField;
    private final Consumer<String> onColorChanged;
    private final Text label;

    public ColorPickerWidget(TextRenderer textRenderer,
                             int x,
                             int y,
                             int width,
                             int height,
                             Text label,
                             String currentValue,
                             Consumer<String> onColorChanged) {
        super(x, y, width, height, Text.empty());
        this.textRenderer = textRenderer;
        this.onColorChanged = onColorChanged;
        this.label = label == null ? Text.empty() : label;

        this.textField = new TextFieldWidget(textRenderer, x, y, INPUT_WIDTH, Math.max(16, height - 2), Text.literal("#RRGGBB"));
        this.textField.setMaxLength(7);
        this.textField.setText(sanitizeColor(currentValue));
        this.textField.setChangedListener(value -> {
            String candidate = normalize(value);
            if (isValidHex(candidate) && this.onColorChanged != null) {
                this.onColorChanged.accept(candidate.toUpperCase(Locale.ROOT));
            }
        });

        updateTextFieldLayout(x, y);
    }

    public String getColor() {
        String candidate = normalize(this.textField.getText());
        return isValidHex(candidate) ? candidate.toUpperCase(Locale.ROOT) : "#FF0000";
    }

    public void setPosition(int x, int y) {
        this.setX(x);
        this.setY(y);
        updateTextFieldLayout(x, y);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int previewColor = parseColor(getColor());

        context.fill(x, y, x + this.width, y + this.height, 0xAA151515);
        context.fill(x, y, x + this.width, y + 1, 0xFF424242);
        context.fill(x, y + this.height - 1, x + this.width, y + this.height, 0xFF424242);
        context.fill(x, y, x + 1, y + this.height, 0xFF424242);
        context.fill(x + this.width - 1, y, x + this.width, y + this.height, 0xFF424242);

        int labelAreaWidth = getLabelAreaWidth();
        Text renderLabel = getRenderLabel(labelAreaWidth);
        RenderHelper.drawText(context, this.textRenderer, renderLabel, x + 2, y + Math.max(0, (this.height - 8) / 2), 0xFFFFFFFF);

        int previewSize = Math.max(12, this.height - 4);
        int previewX = x + labelAreaWidth + INNER_GAP;
        int previewY = y + 2;
        context.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, 0xFF000000 | previewColor);
        context.fill(previewX, previewY, previewX + previewSize, previewY + 1, 0xFFFFFFFF);
        context.fill(previewX, previewY + previewSize - 1, previewX + previewSize, previewY + previewSize, 0xFFFFFFFF);

        this.textField.render(context, mouseX, mouseY, delta);

        if (!isValidHex(normalize(this.textField.getText()))) {
            RenderHelper.drawText(context, this.textRenderer, Text.literal("!"), x + this.width - 10, y + (this.height / 2) - 4, 0xFFFF6666);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        boolean handled = this.textField.mouseClicked(click, doubleClick);
        this.setFocused(this.textField.isFocused());
        return handled || super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return this.textField.mouseReleased(click) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return this.textField.mouseDragged(click, deltaX, deltaY) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        return this.textField.keyPressed(keyInput) || super.keyPressed(keyInput);
    }

    @Override
    public boolean charTyped(CharInput charInput) {
        return this.textField.charTyped(charInput) || super.charTyped(charInput);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        this.textField.setFocused(focused);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
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

    private Text getRenderLabel(int labelAreaWidth) {
        String raw = this.label.getString();
        if (raw == null || raw.isBlank()) {
            return Text.empty();
        }

        String text = raw;
        if (this.textRenderer.getWidth(text) > labelAreaWidth) {
            int trimmedWidth = Math.max(10, labelAreaWidth - this.textRenderer.getWidth("…"));
            text = this.textRenderer.trimToWidth(text, trimmedWidth) + "…";
        }
        return Text.literal(text);
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
