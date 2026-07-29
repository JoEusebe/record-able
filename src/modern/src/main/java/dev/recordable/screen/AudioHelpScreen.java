package dev.recordable.screen;

import dev.recordable.AudioCapture;
import dev.recordable.RecordableConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Help screen with platform-specific instructions for enabling audio capture.
 */
public final class AudioHelpScreen extends Screen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;

    private final Screen parent;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int scrollOffset;
    private int contentHeight;
    private int bodyTop;
    private int bodyBottom;

    private final List<HelpLine> helpLines = new ArrayList<>();

    public AudioHelpScreen(Screen parent) {
        super(Component.translatable("screen.recordable.audio_help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.helpLines.clear();
        this.scrollOffset = 0;

        this.panelWidth = Math.max(340, Math.min((int) (this.width * 0.80D), 600));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = Math.max(8, (int) (this.height * 0.05D));
        this.panelBottom = Math.min(this.height - 8, this.panelTop + Math.max(300, (int) (this.height * 0.88D)));
        this.bodyTop = this.panelTop + 24;
        this.bodyBottom = this.panelBottom - 34;

        addHeader("Audio Capture - How It Works");
        addBlank();
        addHighlight("OpenAL Loopback Capture (Default)");
        addText("Record-able captures audio DIRECTLY from Minecraft's");
        addText("audio engine using OpenAL's ALC_SOFT_loopback extension.");
        addBlank();
        addText("What this means for you:");
        addText("  No Stereo Mix setup needed");
        addText("  No virtual cables or BlackHole required");
        addText("  No system-wide audio routing tricks");
        addText("  Works the same way on every platform");
        addBlank();
        addText("All game sounds, music, ambient effects, and mod");
        addText("sounds are captured at full digital quality:");
        addText("  48 kHz, Stereo, 16-bit PCM");
        addBlank();
        addHighlight("Why This Is Better");
        addText("Older recorders relied on Stereo Mix or BlackHole to");
        addText("pull audio from the speakers. That approach is fragile,");
        addText("noisy, and tied to OS volume sliders.");
        addBlank();
        addText("Loopback capture taps into the audio mixer BEFORE the");
        addText("sound leaves Minecraft. The result: clean audio at");
        addText("100% volume, regardless of how loud you set Windows or");
        addText("your phone.");
        addBlank();

        String platform = AudioCapture.getPlatform();
        switch (platform) {
            case "windows" -> buildWindowsHelp();
            case "linux" -> buildLinuxHelp();
            case "macos" -> buildMacOSHelp();
            case "android" -> buildAndroidHelp();
            default -> addWarning("Unsupported platform for audio: " + platform);
        }
        addBlank();

        addHeader("General Troubleshooting");
        addText("1. Verify 'Capture Audio' is ON in settings");
        addText("2. Make sure Minecraft sound is NOT muted in");
        addText("   Options > Music & Sounds");
        addText("3. Click 'Test Audio' to verify the recorder is");
        addText("   receiving samples");
        addText("4. Restart Minecraft once if you toggled audio mods");
        addText("5. If you hear no audio in the recording, check that");
        addText("   the master volume slider in-game is above 0%");
        addText("6. Check latest.log for any audio warnings");
        addBlank();

        addHeader("Volume Slider");
        addText("Use the Audio Volume slider (0 to 200%) to adjust the");
        addText("recorded audio level:");
        addText("  100% = normal volume (default)");
        addText("  150% = boost quiet audio by 1.5x");
        addText("  50%  = reduce loud audio by half");
        addText("  0%   = muted (no audio in recording)");
        addBlank();
        addText("This slider only changes the RECORDED audio. It does");
        addText("not affect what you hear while playing.");
        addBlank();

        addHeader("Mono vs Stereo");
        addText("Stereo (default) is recommended for music and most");
        addText("gameplay. Switch to Mono if you only need voice or");
        addText("if your output device is mono.");

        this.contentHeight = helpLines.size() * 12 + 10;

        int buttonWidth = 120;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.recordable.audio_help.back"),
                button -> onClose()
        ).bounds((this.width - buttonWidth) / 2, this.panelBottom - 28, buttonWidth, 20).build());
    }

    private void buildWindowsHelp() {
        addHeader("Windows");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works out of the box on Windows.");
        addText("You do NOT need to enable Stereo Mix, install");
        addText("VB-Cable, or change any audio routing.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("   (check the Music & Sounds menu)");
        addText("3. Click 'Test Audio' in the settings screen");
    }

    private void buildLinuxHelp() {
        addHeader("Linux");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works on PulseAudio, PipeWire, and");
        addText("ALSA without any extra configuration.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("3. Click 'Test Audio' in the settings screen");
        addText("4. If using PipeWire, make sure the OpenAL backend");
        addText("   is not forced to a specific device");
    }

    private void buildMacOSHelp() {
        addHeader("macOS");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works out of the box on macOS.");
        addText("BlackHole and Multi-Output Devices are NOT required.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("3. Click 'Test Audio' in the settings screen");
    }

    private void buildAndroidHelp() {
        addHeader("Android (PojavLauncher / Zalith / FCL)");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback is automatic on Android launchers.");
        addText("You do NOT need root, special permissions, or any");
        addText("extra audio plugin. Just keep 'Capture Audio' ON.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("   (turn up the master volume slider)");
        addText("3. Click 'Test Audio' to verify the recorder");
        addText("   is receiving samples");
        addBlank();
        addWarning("Tip: Some launchers need OpenAL Soft enabled in");
        addWarning("their launcher settings. If audio is silent, look");
        addWarning("for an OpenAL or Sound option in your launcher.");
    }

    private void addHeader(String text) { helpLines.add(new HelpLine(text, HEADER_COLOR, true)); }
    private void addText(String text) { helpLines.add(new HelpLine(text, TEXT_COLOR, false)); }
    private void addHighlight(String text) { helpLines.add(new HelpLine(text, HIGHLIGHT_COLOR, false)); }
    private void addWarning(String text) { helpLines.add(new HelpLine(text, WARNING_COLOR, false)); }
    private void addBlank() { helpLines.add(new HelpLine("", TEXT_COLOR, false)); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, this.contentHeight - (this.bodyBottom - this.bodyTop));
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int delta = (int) Math.round(verticalAmount * -20.0D);
        if (delta == 0) delta = verticalAmount > 0 ? -20 : 20;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        return true;
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

        int textLeft = this.panelLeft + 14;
        for (int i = 0; i < helpLines.size(); i++) {
            int y = this.bodyTop + (i * 12) - this.scrollOffset;
            if (y < this.bodyTop - 12 || y > this.bodyBottom + 2) continue;

            HelpLine line = helpLines.get(i);
            if (line.text.isEmpty()) continue;

            if (line.bold) {
                context.text(this.font, Component.literal("§l" + line.text), textLeft, y, line.color, true);
            } else {
                context.text(this.font, Component.literal(line.text), textLeft, y, line.color, true);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    private record HelpLine(String text, int color, boolean bold) {}
}
