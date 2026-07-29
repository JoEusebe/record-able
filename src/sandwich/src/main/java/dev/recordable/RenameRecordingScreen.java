package dev.recordable;

import dev.recordable.theme.ThemedButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * Post-recording rename prompt.
 *
 * <p>Shown right after a recording is manually stopped and saved (Feature 1).
 * The player can type a new name for the file and click "Save", or keep the
 * automatically generated name by clicking "Cancel" (which reverts edits) or pressing
 * Escape. The actual file move is delegated to
 * {@link RecordingManager#renameRecording(Path, String)} so the same logic and
 * internal bookkeeping are reused everywhere.</p>
 */
public final class RenameRecordingScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 150;
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF444444;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;

    private final Screen parent;
    private final Path savedFile;
    private final String originalBaseName;
    private final String extension;

    private TextFieldWidget nameField;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private int panelX;
    private int panelY;

    private RenameRecordingScreen(Screen parent, Path savedFile) {
        super(Text.literal("Name your recording"));
        this.parent = parent;
        this.savedFile = savedFile;
        String fileName = savedFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        this.originalBaseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        this.extension = dot > 0 ? fileName.substring(dot) : "";
    }

    /** Opens the rename prompt for the given saved file on the client thread. */
    public static void openFor(MinecraftClient client, Path savedFile) {
        if (client == null || savedFile == null) {
            return;
        }
        Screen parent = client.currentScreen;
        client.setScreen(new RenameRecordingScreen(parent, savedFile));
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        int fieldWidth = PANEL_WIDTH - 40;
        int fieldX = this.panelX + 20;
        int fieldY = this.panelY + 52;

        this.nameField = new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 20,
                Text.literal("Recording name"));
        this.nameField.setMaxLength(200);
        this.nameField.setText(this.originalBaseName);
        this.addDrawableChild(this.nameField);
        this.setInitialFocus(this.nameField);

        int buttonWidth = 150;
        int buttonHeight = 20;
        int gap = 10;
        int totalWidth = buttonWidth * 2 + gap;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.panelY + PANEL_HEIGHT - buttonHeight - 16;

        this.saveButton = ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                Text.literal("Save"), button -> applyRename());
        this.addDrawableChild(this.saveButton);
        this.cancelButton = ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                Text.literal("Cancel"), button -> cancelEdit());
        this.addDrawableChild(this.cancelButton);
    }

    private void applyRename() {
        String newName = this.nameField == null ? "" : this.nameField.getText();
        Path result = RecordingManager.getInstance().renameRecording(this.savedFile, newName);
        if (result == null) {
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.client,
                    "\u00a7cCould not rename recording. Kept original name.", false);
        }
        closeToParent();
    }

    private void cancelEdit() {
        // Revert any edits the user made and keep the original name.
        if (this.nameField != null) {
            this.nameField.setText(this.originalBaseName);
        }
        keepOriginal();
    }

    private void keepOriginal() {
        closeToParent();
    }

    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        // Enter / numpad Enter confirms the rename.
        int keyCode = keyInput.key();
        if (keyCode == 257 || keyCode == 335) {
            applyRename();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, PANEL_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, BORDER_COLOR);
        context.fill(this.panelX, this.panelY + PANEL_HEIGHT - 1, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX + PANEL_WIDTH - 1, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Name your recording"),
                this.width / 2, this.panelY + 14, TITLE_COLOR);
        context.drawText(this.textRenderer,
                Text.literal("Rename the saved file (extension " + this.extension + " is kept):"),
                this.panelX + 20, this.panelY + 36, TEXT_COLOR, false);

        // Re-draw the text field on top of the panel background.
        if (this.nameField != null) {
            this.nameField.render(context, mouseX, mouseY, delta);
        }

        // Re-draw the buttons on top so the panel fill does not dim them.
        if (this.saveButton != null) {
            this.saveButton.render(context, mouseX, mouseY, delta);
        }
        if (this.cancelButton != null) {
            this.cancelButton.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public void close() {
        // Escape / close keeps the original name.
        keepOriginal();
    }
}
