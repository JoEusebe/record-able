package dev.recordable;

import dev.recordable.theme.ThemedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

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

    private EditBox nameField;
    private Button saveButton;
    private int panelX;
    private int panelY;

    private RenameRecordingScreen(Screen parent, Path savedFile) {
        super(Component.literal("Name your recording"));
        this.parent = parent;
        this.savedFile = savedFile;
        String fileName = savedFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        this.originalBaseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        this.extension = dot > 0 ? fileName.substring(dot) : "";
    }

    /** Opens the rename prompt for the given saved file on the client thread. */
    public static void openFor(Minecraft client, Path savedFile) {
        if (client == null || savedFile == null) {
            return;
        }
        Screen parent = VersionHelper.currentScreen(client);
        client.setScreenAndShow(new RenameRecordingScreen(parent, savedFile));
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        int fieldWidth = PANEL_WIDTH - 40;
        int fieldX = this.panelX + 20;
        int fieldY = this.panelY + 52;

        this.nameField = new EditBox(this.font, fieldX, fieldY, fieldWidth, 20,
                Component.literal("Recording name"));
        this.nameField.setMaxLength(200);
        this.nameField.setValue(this.originalBaseName);
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        int buttonWidth = 150;
        int buttonHeight = 20;
        int gap = 10;
        int totalWidth = buttonWidth * 2 + gap;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.panelY + PANEL_HEIGHT - buttonHeight - 16;

        this.saveButton = ThemedButton.create(startX, buttonY, buttonWidth, buttonHeight,
                Component.literal("Save"), button -> applyRename());
        this.addRenderableWidget(this.saveButton);
        this.addRenderableWidget(ThemedButton.create(startX + buttonWidth + gap, buttonY, buttonWidth, buttonHeight,
                Component.literal("Cancel"), button -> cancelEdit()));
    }

    private void applyRename() {
        String newName = this.nameField == null ? "" : this.nameField.getValue();
        Path result = RecordingManager.getInstance().renameRecording(this.savedFile, newName);
        if (result == null) {
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, this.minecraft,
                    "\u00a7cCould not rename recording. Kept original name.", false);
        }
        closeToParent();
    }

    private void cancelEdit() {
        // Revert any edits the user made and keep the original name.
        if (this.nameField != null) {
            this.nameField.setValue(this.originalBaseName);
        }
        keepOriginal();
    }

    private void keepOriginal() {
        closeToParent();
    }

    private void closeToParent() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        // Enter / numpad Enter confirms the rename.
        int keyCode = keyEvent.key();
        if (keyCode == 257 || keyCode == 335) {
            applyRename();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Draw the darkened menu background and our panel FIRST, then let the
        // widgets (text field + buttons) render on top via super so they clearly
        // stand out above the panel instead of appearing dimmed underneath it.
        this.extractMenuBackground(context);

        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, PANEL_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, BORDER_COLOR);
        context.fill(this.panelX, this.panelY + PANEL_HEIGHT - 1, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX, this.panelY, this.panelX + 1, this.panelY + PANEL_HEIGHT, BORDER_COLOR);
        context.fill(this.panelX + PANEL_WIDTH - 1, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, BORDER_COLOR);

        context.centeredText(this.font, Component.literal("Name your recording"),
                this.width / 2, this.panelY + 14, TITLE_COLOR);
        context.text(this.font,
                Component.literal("Rename the saved file (extension " + this.extension + " is kept):"),
                this.panelX + 20, this.panelY + 36, TEXT_COLOR);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        // Escape / close keeps the original name.
        keepOriginal();
    }
}
