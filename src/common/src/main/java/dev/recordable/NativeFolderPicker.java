package dev.recordable;

import java.io.File;
import java.util.function.Consumer;

/**
 * Native OS folder / file picker, shared across all variants.
 *
 * <p>Uses LWJGL's bundled {@code tinyfd} (TinyFileDialogs), which Minecraft ships
 * on every desktop platform, to show the real operating-system folder chooser.
 * This lets the user relocate the recordings folder (or point at an FFmpeg binary)
 * by browsing, instead of typing a raw path.</p>
 *
 * <p>The dialog blocks until the user chooses, so it always runs on a short-lived
 * daemon thread to avoid freezing the render thread. The result is delivered to a
 * callback; callers that touch game state should marshal that back onto the client
 * thread themselves. On Android there is no native dialog, so {@link #isSupported()}
 * returns {@code false} and callers keep the manual text-entry path.</p>
 */
public final class NativeFolderPicker {

    private NativeFolderPicker() {}

    /** True when a native picker can be shown (desktop only, never Android). */
    public static boolean isSupported() {
        if (PlatformUtils.isAndroid()) return false;
        try {
            Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Opens the native folder chooser. {@code onResult} receives the chosen
     * absolute path, or {@code null} if the user cancelled or the dialog failed.
     */
    public static void pickFolder(String title, String defaultPath, Consumer<String> onResult) {
        if (!isSupported()) {
            onResult.accept(null);
            return;
        }
        runDialog("Record-able-FolderPicker", () -> {
            String def = normalizeDefaultDir(defaultPath);
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(
                    title == null ? "Select folder" : title, def);
        }, onResult);
    }

    /**
     * Opens the native file chooser (single selection). {@code onResult} receives
     * the chosen absolute path, or {@code null} if the user cancelled or it failed.
     */
    public static void pickFile(String title, String defaultPath, Consumer<String> onResult) {
        if (!isSupported()) {
            onResult.accept(null);
            return;
        }
        runDialog("Record-able-FilePicker", () -> {
            String def = defaultPath == null ? "" : defaultPath;
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                    title == null ? "Select file" : title, def, null, null, false);
        }, onResult);
    }

    private static void runDialog(String threadName, DialogCall call, Consumer<String> onResult) {
        Thread t = new Thread(() -> {
            String result = null;
            try {
                result = call.show();
            } catch (Throwable e) {
                RecordableMod.LOGGER.warn("[NativeFolderPicker] Native dialog failed: {}", e.getMessage());
            }
            try {
                onResult.accept(result);
            } catch (Throwable e) {
                RecordableMod.LOGGER.warn("[NativeFolderPicker] Picker callback failed: {}", e.getMessage());
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    private static String normalizeDefaultDir(String defaultPath) {
        if (defaultPath == null || defaultPath.isBlank()) return "";
        String def = defaultPath.trim();
        // tinyfd treats a trailing separator as "open inside this directory".
        if (!def.endsWith(File.separator)) def = def + File.separator;
        return def;
    }

    @FunctionalInterface
    private interface DialogCall {
        String show() throws Throwable;
    }
}
