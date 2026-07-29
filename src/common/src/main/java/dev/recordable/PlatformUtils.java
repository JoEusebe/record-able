package dev.recordable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Central platform detection utility for cross-platform support.
 *
 * <p>Detects the current operating system, architecture, and available
 * system tools (FFmpeg). Provides helper methods for platform-specific behavior.</p>
 */
public final class PlatformUtils {

    /** Supported platform categories. */
    public enum Platform {
        WINDOWS("Windows"),
        LINUX("Linux"),
        MACOS("macOS"),
        ANDROID("Android"),
        UNKNOWN("Unknown");

        private final String displayName;

        Platform(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Identifies the OpenGL renderer used on Android.
     *
     * <p>GLES translation layers (MobileGlues, GL4ES) only accept {@code GL_RGBA} for
     * {@code glReadPixels}. Native OpenGL providers (Zink, VirGL/software) accept
     * the faster {@code GL_BGR} path used on desktop.</p>
     */
    public enum AndroidRenderer {
        /** MobileGlues ANGLE-based translation layer (Zalith, PojavLauncher plugins). */
        MOBILEGLUES(true),
        /** GL4ES legacy GLES-to-GL translation layer. */
        GL4ES(true),
        /** Mesa Zink: OpenGL over Vulkan (Turnip/Freedreno on Adreno GPUs). */
        ZINK(false),
        /** VirGL or software rasterizer (llvmpipe/softpipe). */
        VIRGL_OR_SOFTWARE(false),
        /** Renderer could not be identified; assumes GLES constraints for safety. */
        UNKNOWN(true);

        private final boolean requiresRgbaReadback;

        AndroidRenderer(boolean requiresRgbaReadback) {
            this.requiresRgbaReadback = requiresRgbaReadback;
        }

        /** Returns {@code true} if this renderer only supports {@code GL_RGBA} readback, not {@code GL_BGR}. */
        public boolean requiresRgbaReadback() {
            return requiresRgbaReadback;
        }
    }

    /**
     * Detects the Android OpenGL renderer using {@code GL_RENDERER} string and environment variables.
     *
     * <p>The {@code glRendererString} parameter should be obtained by calling
     * {@code GL11.glGetString(GL11.GL_RENDERER)} from the render thread. Passing
     * {@code null} falls back to environment-variable-only detection.</p>
     *
     * @param glRendererString the {@code GL_RENDERER} string, or {@code null} if unavailable
     * @return detected renderer type; never {@code null}
     */
    public static AndroidRenderer detectAndroidRenderer(String glRendererString) {
        if (!isAndroid()) return AndroidRenderer.UNKNOWN;

        String lower = (glRendererString != null ? glRendererString : "").toLowerCase(Locale.ROOT);
        String libglEgl = System.getenv("LIBGL_EGL");
        if (libglEgl == null) libglEgl = "";
        libglEgl = libglEgl.toLowerCase(Locale.ROOT);

        // Zink must be checked before ANGLE because some builds report both strings.
        if (lower.contains("zink")) {
            return AndroidRenderer.ZINK;
        }
        // MobileGlues uses ANGLE internally; LIBGL_EGL is the most reliable signal.
        if (libglEgl.contains("mobileglues") || lower.contains("angle")) {
            return AndroidRenderer.MOBILEGLUES;
        }
        if (libglEgl.contains("gl4es") || lower.contains("gl4es")) {
            return AndroidRenderer.GL4ES;
        }
        if (lower.contains("virgl") || lower.contains("llvmpipe") || lower.contains("softpipe")) {
            return AndroidRenderer.VIRGL_OR_SOFTWARE;
        }

        return AndroidRenderer.UNKNOWN;
    }

    private static volatile Platform cachedPlatform;

    private PlatformUtils() {
    }

    /**
     * Detects the current platform. Result is cached after the first call.
     *
     * <p>Android detection checks for ARM/aarch64 architecture on Linux combined
     * with the presence of Android-specific paths (e.g., PojavLauncher on Android).</p>
     */
    public static Platform detectPlatform() {
        Platform cached = cachedPlatform;
        if (cached != null) {
            return cached;
        }

        Platform detected = probePlatform();
        cachedPlatform = detected;
        RecordableMod.LOGGER.info("Detected platform: {} (os.name={}, os.arch={})",
                detected.displayName(),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"));
        return detected;
    }

    private static Platform probePlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (isAndroidEnvironment(osName, osArch)) {
            return Platform.ANDROID;
        }

        if (osName.contains("win")) {
            return Platform.WINDOWS;
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return Platform.MACOS;
        }
        if (osName.contains("linux") || osName.contains("nix") || osName.contains("nux")) {
            return Platform.LINUX;
        }

        return Platform.UNKNOWN;
    }

    /**
     * Heuristic to detect Android environments (PojavLauncher, Zalith, etc.).
     *
     * <p>Uses multiple detection methods to maximize compatibility across different
     * Android Minecraft launchers. Each method is logged for diagnostics.</p>
     *
     * <p>Detection methods:</p>
     * <ol>
     *   <li>VM name check (Dalvik/ART)</li>
     *   <li>java.vendor / java.vm.vendor check for "Android"</li>
     *   <li>ANDROID_DATA environment variable</li>
     *   <li>Android system file /system/build.prop</li>
     *   <li>Android launcher package paths (PojavLauncher, Zalith 1/2, etc.)</li>
     *   <li>Generic Android filesystem paths (/data/data, /sdcard, etc.)</li>
     *   <li>Current working directory heuristic (inside /data/data/)</li>
     * </ol>
     */
    private static boolean isAndroidEnvironment(String osName, String osArch) {
        RecordableMod.LOGGER.info("[AndroidDetect] === Android Detection Start ===");
        RecordableMod.LOGGER.info("[AndroidDetect] os.name={}", System.getProperty("os.name", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] os.arch={}", System.getProperty("os.arch", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] java.vm.name={}", System.getProperty("java.vm.name", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] java.vm.vendor={}", System.getProperty("java.vm.vendor", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] java.vendor={}", System.getProperty("java.vendor", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] java.home={}", System.getProperty("java.home", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] user.dir={}", System.getProperty("user.dir", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] user.home={}", System.getProperty("user.home", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] java.runtime.name={}", System.getProperty("java.runtime.name", "?"));
        RecordableMod.LOGGER.info("[AndroidDetect] ANDROID_DATA={}", System.getenv("ANDROID_DATA"));
        RecordableMod.LOGGER.info("[AndroidDetect] ANDROID_ROOT={}", System.getenv("ANDROID_ROOT"));

        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        if (vmName.contains("dalvik") || vmName.contains("art")) {
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via java.vm.name: {}", vmName);
            return true;
        }

        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
        if (vendor.contains("android") || vmVendor.contains("android")) {
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via vendor: java.vendor={}, java.vm.vendor={}", vendor, vmVendor);
            return true;
        }

        String androidData = System.getenv("ANDROID_DATA");
        String androidRoot = System.getenv("ANDROID_ROOT");
        if ((androidData != null && !androidData.isEmpty()) || (androidRoot != null && !androidRoot.isEmpty())) {
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via env: ANDROID_DATA={}, ANDROID_ROOT={}", androidData, androidRoot);
            return true;
        }

        if (Files.exists(Path.of("/system/build.prop"))) {
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via /system/build.prop");
            return true;
        }

        String[] launcherPaths = {
            "/data/data/net.kdt.pojavlaunch",
            "/data/user/0/net.kdt.pojavlaunch",
            "/data/data/com.movtery.pojavzh",
            "/data/user/0/com.movtery.pojavzh",
            "/data/data/com.movtery.zalern",
            "/data/user/0/com.movtery.zalern",
            "/data/data/com.movtery.zalith",
            "/data/user/0/com.movtery.zalith",
            "/data/data/com.movtery.zalithlauncher",
            "/data/user/0/com.movtery.zalithlauncher",
            "/data/data/com.movtery.zalithlauncher.v2",
            "/data/user/0/com.movtery.zalithlauncher.v2",
            "/data/data/com.tungsten.fcl",
            "/data/user/0/com.tungsten.fcl",
        };
        for (String launcherPath : launcherPaths) {
            if (Files.exists(Path.of(launcherPath))) {
                RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via launcher path: {}", launcherPath);
                return true;
            }
        }

        boolean isLinux = osName.contains("linux");
        boolean isArm = osArch.contains("aarch64") || osArch.contains("arm");
        if (isLinux && isArm) {
            if (Files.exists(Path.of("/data/data")) ||
                Files.exists(Path.of("/sdcard")) ||
                Files.exists(Path.of("/storage/emulated"))) {
                RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via Linux+ARM + Android paths");
                return true;
            }
        }

        String userDir = System.getProperty("user.dir", "");
        String javaHome = System.getProperty("java.home", "");
        if (userDir.startsWith("/data/data/") || userDir.startsWith("/data/user/") ||
            javaHome.startsWith("/data/data/") || javaHome.startsWith("/data/user/")) {
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via working directory inside /data/: userDir={}, javaHome={}", userDir, javaHome);
            return true;
        }

        try {
            Class.forName("android.os.Build");
            RecordableMod.LOGGER.info("[AndroidDetect] ✓ Detected via android.os.Build class");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        RecordableMod.LOGGER.info("[AndroidDetect] ✗ Not detected as Android");
        RecordableMod.LOGGER.info("[AndroidDetect] === Android Detection End ===");
        return false;
    }

    public static boolean isWindows() {
        return detectPlatform() == Platform.WINDOWS;
    }

    public static boolean isLinux() {
        return detectPlatform() == Platform.LINUX;
    }

    public static boolean isMacOS() {
        return detectPlatform() == Platform.MACOS;
    }

    /**
     * Returns {@code true} on an Apple Silicon (arm64) Mac, {@code false} on an
     * Intel (x86_64) Mac or any non-Mac platform.
     *
     * <p>Note: if Minecraft is running under Rosetta 2 (an x86_64 JVM on an
     * Apple Silicon machine), {@code os.arch} reports {@code x86_64} and this
     * returns {@code false}. That is the correct behaviour - a Rosetta JVM should
     * pair with the Intel FFmpeg build so both run under the same translation.</p>
     */
    public static boolean isMacArm() {
        if (detectPlatform() != Platform.MACOS) return false;
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return osArch.contains("aarch64") || osArch.contains("arm64") || osArch.contains("arm");
    }

    public static boolean isAndroid() {
        return detectPlatform() == Platform.ANDROID;
    }

    /**
     * Returns {@code true} if a Vulkan-based renderer replacement is installed.
     *
     * <p>Record-able primarily captures frames from OpenGL readback
     * ({@code glReadPixels}). Renderer mods that replace OpenGL with Vulkan
     * (VulkanMod, and the experimental Vulkanite/Aftermath paths) bypass that
     * framebuffer. When this returns {@code true}, desktop builds switch to a
     * window-capture fallback path, which is less efficient but keeps recording
     * functional.</p>
     */
    public static boolean isVulkanRendererLoaded() {
        try {
            net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            return loader.isModLoaded("vulkanmod")
                    || loader.isModLoaded("vulkanite")
                    || loader.isModLoaded("vulkan-mod");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Copies a recording to the Android gallery folder so it appears in the
     * user's gallery/photos app automatically.
     *
     * <p>Android's Media Scanner only indexes certain public directories. By
     * copying finished recordings to {@code /storage/emulated/0/Movies/Record-able/},
     * they become visible in the gallery without needing file-manager navigation.</p>
     *
     * <p>This method is a best-effort operation. On Android 10+ with scoped storage,
     * write access to {@code /storage/emulated/0/Movies/} may be restricted for some
     * launcher configurations. If the copy fails (permission denial, out of space,
     * etc.), the original recording in the mod's directory remains intact and the
     * method logs a warning and returns {@code false}.</p>
     *
     * @param videoPath the finished recording file to copy
     * @return {@code true} if the file was successfully copied to the gallery folder;
     *         {@code false} if not on Android, copy failed, or permissions blocked
     */
    public static boolean copyToAndroidGallery(Path videoPath) {
        if (!isAndroid() || videoPath == null || !Files.isRegularFile(videoPath)) {
            return false;
        }

        // Candidate public directories, in preference order. Movies/Record-able is the
        // conventional home for recordings, but DCIM is scanned more aggressively by gallery
        // apps and is sometimes writable when Movies is blocked by scoped storage, so we use
        // it as a fallback location.
        Path[] candidateDirs = new Path[] {
            Paths.get("/storage/emulated/0/Movies/Record-able"),
            Paths.get("/storage/emulated/0/DCIM/Record-able")
        };

        for (Path galleryDir : candidateDirs) {
            try {
                if (!Files.exists(galleryDir)) {
                    try {
                        Files.createDirectories(galleryDir);
                        RecordableMod.LOGGER.info("[AndroidGallery] Created directory: {}", galleryDir);
                    } catch (IOException e) {
                        RecordableMod.LOGGER.warn("[AndroidGallery] Could not create {} ({}). Trying next location.",
                                galleryDir, e.getMessage());
                        continue;
                    }
                }

                // Copy the video file
                Path destination = galleryDir.resolve(videoPath.getFileName());
                Files.copy(videoPath, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                long sizeMB = Files.size(destination) / (1024 * 1024);
                RecordableMod.LOGGER.info("[AndroidGallery] ✓ Copied to gallery: {} ({} MB)",
                        destination, sizeMB);

                // Trigger media indexing so the file appears in gallery apps immediately
                triggerMediaScan(destination);
                return true;

            } catch (IOException e) {
                RecordableMod.LOGGER.warn("[AndroidGallery] Failed to copy to {} ({}: {}). Trying next location.",
                        galleryDir, e.getClass().getSimpleName(), e.getMessage());
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("[AndroidGallery] Unexpected error copying to {} ({}). Trying next location.",
                        galleryDir, t.toString());
            }
        }

        RecordableMod.LOGGER.warn("[AndroidGallery] Could not copy recording to any public gallery directory. " +
                "Original recording remains in the mod folder.");
        return false;
    }

    /**
     * Triggers Android to index a file so it appears in gallery apps immediately.
     *
     * <p>Android 11+ (API 30+) ignores the legacy
     * {@code android.intent.action.MEDIA_SCANNER_SCAN_FILE} broadcast, and we cannot use
     * {@code MediaScannerConnection} because the Minecraft JVM has no Android
     * {@code Context}. This method therefore tries several Context-free strategies in
     * order of reliability, stopping at the first one that succeeds:</p>
     *
     * <ol>
     *   <li><b>MediaStore insert</b> - {@code content insert} against
     *       {@code content://media/external/video/media}. This writes the file directly
     *       into the MediaStore database via the shell {@code content} tool, which is the
     *       modern, supported way to register media without a Context. Works on
     *       Android 10-13.</li>
     *   <li><b>Legacy broadcast</b> - {@code am broadcast MEDIA_SCANNER_SCAN_FILE}. Kept
     *       only as a fallback for Android 9 and older where it still functions.</li>
     *   <li><b>Directory touch</b> - updates the parent directory's modification time so
     *       file managers / launchers that watch for directory changes pick the file up.</li>
     * </ol>
     *
     * <p>Every strategy is best-effort and individually logged so we can tell from the
     * device log which one actually worked on a given launcher/Android version. Even if
     * all of them fail, the file is already copied to a public directory and will appear
     * after the next automatic device scan.</p>
     *
     * @param filePath absolute path to the file to scan
     */
    private static void triggerMediaScan(Path filePath) {
        if (!isAndroid() || filePath == null) {
            return;
        }

        String absPath = filePath.toAbsolutePath().toString();
        String fileName = filePath.getFileName().toString();
        String mimeType = guessVideoMimeType(fileName);

        // Strategy 1: modern MediaStore insert via the shell `content` tool (Android 10+).
        if (mediaStoreInsert(absPath, fileName, mimeType)) {
            RecordableMod.LOGGER.info("[AndroidGallery] Indexed via MediaStore insert: {}", fileName);
            return;
        }

        // Strategy 2: legacy broadcast (only effective on Android 9 and below).
        if (legacyMediaScanBroadcast(absPath)) {
            RecordableMod.LOGGER.info("[AndroidGallery] Indexed via legacy MEDIA_SCANNER broadcast: {}", fileName);
            return;
        }

        // Strategy 3: bump the parent directory mtime so directory-watching apps notice.
        if (touchParentDirectory(filePath)) {
            RecordableMod.LOGGER.info("[AndroidGallery] Parent directory touched to prompt rescan: {}",
                    filePath.getParent());
            return;
        }

        RecordableMod.LOGGER.info("[AndroidGallery] No immediate media-scan method succeeded for {}. " +
                "File is saved and will appear after the next automatic device scan or gallery refresh.", fileName);
    }

    /**
     * Registers a video file with the MediaStore using the shell {@code content insert}
     * command. This is the modern, Context-free replacement for the deprecated media-scan
     * broadcast and works on Android 10-13 because MediaProvider honors the legacy
     * {@code _data} column for inserts coming from the {@code content} tool.
     *
     * @return {@code true} only if the insert command exits cleanly (0)
     */
    private static boolean mediaStoreInsert(String absPath, String fileName, String mimeType) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("content");
            cmd.add("insert");
            cmd.add("--uri");
            cmd.add("content://media/external/video/media");
            cmd.add("--bind");
            cmd.add("_data:s:" + absPath);
            cmd.add("--bind");
            cmd.add("mime_type:s:" + mimeType);
            cmd.add("--bind");
            cmd.add("_display_name:s:" + fileName);
            cmd.add("--bind");
            cmd.add("title:s:" + stripExtension(fileName));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                RecordableMod.LOGGER.debug("[AndroidGallery] MediaStore insert timed out for {}", fileName);
                return false;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                return true;
            }
            RecordableMod.LOGGER.debug("[AndroidGallery] MediaStore insert failed (exit={}) for {}", exit, fileName);
            return false;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[AndroidGallery] MediaStore insert unavailable ({}) for {}",
                    e.getMessage(), fileName);
            return false;
        }
    }

    /**
     * Legacy {@code am broadcast MEDIA_SCANNER_SCAN_FILE}. Deprecated and ignored on
     * Android 11+, but kept as a fallback because it still works on Android 9 and older.
     *
     * @return {@code true} only if the broadcast command exits cleanly (0)
     */
    private static boolean legacyMediaScanBroadcast(String absPath) {
        try {
            String fileUri = "file://" + absPath;
            ProcessBuilder pb = new ProcessBuilder(
                "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", fileUri
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[AndroidGallery] Legacy media-scan broadcast unavailable ({})",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Updates the parent directory's last-modified timestamp. Some launchers and file
     * managers (including parts of the Pojav/Zalith environment) watch directory mtimes
     * and rescan their contents when they change.
     *
     * @return {@code true} if the timestamp was updated
     */
    private static boolean touchParentDirectory(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return false;
            }
            Files.setLastModifiedTime(parent, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return true;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[AndroidGallery] Could not touch parent directory ({})", e.getMessage());
            return false;
        }
    }

    /** Maps a video file name to a best-guess MIME type for MediaStore registration. */
    private static String guessVideoMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        // Default to MP4, the mod's primary output container.
        return "video/mp4";
    }

    /** Returns the file name without its extension (for the MediaStore title column). */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * @return {@code true} if running on Android and the host launcher is
     *         PojavLauncher (or a close fork that exposes {@code POJAV_NATIVEDIR}).
     */
    public static boolean isPojavLauncher() {
        return isAndroid() && AndroidPlatform.isPojavLauncher();
    }

    /** @return {@code true} if running on Android under the Zalith launcher family. */
    public static boolean isZalithLauncher() {
        return isAndroid() && AndroidPlatform.isZalithLauncher();
    }

    /**
     * @return {@code true} only when running on Android with an ARM CPU
     *         (Bionic libc), as opposed to a standard Linux ARM box (glibc).
     */
    public static boolean isAndroidArm() {
        return AndroidPlatform.isAndroidArm();
    }

    /**
     * @return {@code true} when running on a standard (non-Android) Linux ARM
     *         system, which uses glibc rather than Bionic.
     */
    public static boolean isStandardLinuxArm() {
        return AndroidPlatform.isStandardLinuxArm();
    }

    /**
     * Returns {@code true} if the current platform supports recording.
     *
     * <p>Android is now supported via bundled FFmpeg binaries for ARM architectures
     * (used by PojavLauncher, Zalith, and FoldCraftLauncher). Only {@code UNKNOWN}
     * platforms are unsupported.</p>
     */
    public static boolean isRecordingSupported() {
        Platform platform = detectPlatform();
        return platform != Platform.UNKNOWN;
    }

    /**
     * Returns a platform-specific string identifier compatible with
     * the existing {@code AudioCapture.getPlatform()} format.
     */
    public static String getPlatformId() {
        return switch (detectPlatform()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "macos";
            case ANDROID -> "android";
            case UNKNOWN -> "unknown";
        };
    }

    /**
     * Checks if FFmpeg is available. This delegates to {@link FFmpegEncoder#detectFfmpeg()}.
     *
     * @return {@code true} if FFmpeg was found in PATH or via RECORDABLE_FFMPEG_PATH
     */
    public static boolean isFfmpegAvailable() {
        try {
            if (isAndroid()) {
                return FfmpegBundleManager.isBundledFfmpegAvailable();
            }
            return FFmpegEncoder.detectFfmpeg().found();
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("FFmpeg detection failed", e);
            return false;
        }
    }

    /**
     * Returns a platform-specific hint for installing FFmpeg.
     */
    public static String getFfmpegInstallHint() {
        return switch (detectPlatform()) {
            case WINDOWS -> "Click 'Download FFmpeg' in Record-able settings (auto-downloads from gyan.dev), "
                    + "or install manually from https://www.gyan.dev/ffmpeg/builds/ and add it to PATH.";
            case LINUX -> "Click 'Download FFmpeg' in Record-able settings (auto-downloads from johnvansickle.com), "
                    + "or install via your package manager: sudo apt install ffmpeg / sudo dnf install ffmpeg / sudo pacman -S ffmpeg.";
            case MACOS -> isMacArm()
                    ? "Click 'Download FFmpeg' in Record-able settings (auto-downloads an Apple Silicon "
                            + "arm64 build), or install via Homebrew: brew install ffmpeg."
                    : "Click 'Download FFmpeg' in Record-able settings (auto-downloads an Intel x86_64 "
                            + "build from evermeet.cx), or install via Homebrew: brew install ffmpeg.";
            case ANDROID -> AndroidPlatform.isArm64()
                    ? "Click 'Download FFmpeg' in Record-able settings (auto-downloads an arm64-v8a build), "
                            + "or install Termux and run 'pkg install ffmpeg' then set ffmpegPath to "
                            + "/data/data/com.termux/files/usr/bin/ffmpeg."
                    : "Auto-download is only available on arm64-v8a devices. Install Termux and run "
                            + "'pkg install ffmpeg', then set ffmpegPath to "
                            + "/data/data/com.termux/files/usr/bin/ffmpeg.";
            case UNKNOWN -> "Please install FFmpeg and ensure it is available in your system PATH.";
        };
    }

    /**
     * Returns a platform-specific description of the audio capture method.
     */
    public static String getAudioMethodDescription() {
        return switch (detectPlatform()) {
            case WINDOWS -> "DirectShow (Stereo Mix)";
            case LINUX -> "PulseAudio";
            case MACOS -> "AVFoundation";
            case ANDROID -> "OpenAL Loopback (game audio)";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Returns {@code true} if the current platform is a mobile/resource-constrained device.
     * Currently this means Android, but could be extended for other mobile platforms.
     */
    public static boolean isMobileDevice() {
        return detectPlatform() == Platform.ANDROID;
    }

    /**
     * Returns the maximum memory budget (in megabytes) for the replay buffer
     * based on the current platform and available heap space.
     *
     * <p>Mobile devices get a conservative 200 MB budget. Desktop platforms
     * get up to 1500 MB, capped at 40% of the max JVM heap to leave room for
     * Minecraft itself.</p>
     */
    public static long getReplayBufferMemoryBudgetMB() {
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (isMobileDevice()) {
            // Android: very conservative - cap at 200 MB or 15% of heap, whichever is smaller
            return Math.min(200L, maxHeapMB * 15 / 100);
        }
        // Desktop: up to 1500 MB, but no more than 40% of heap
        return Math.min(1500L, maxHeapMB * 40 / 100);
    }

    /**
     * Returns an estimated per-frame size in bytes for the replay buffer,
     * based on a given width and height. Accounts for mobile downscaling
     * (50% resolution on Android).
     */
    public static long estimateReplayFrameBytes(int width, int height) {
        if (isMobileDevice()) {
            // On mobile, we store frames at 50% resolution → 25% of full pixels
            return (long) (width / 2) * (height / 2) * 3;
        }
        return (long) width * height * 3;
    }

    /**
     * Returns the downscale factor for replay buffer frames.
     * Mobile devices use 2 (50% resolution), desktop uses 1 (full resolution).
     */
    public static int getReplayBufferDownscaleFactor() {
        return isMobileDevice() ? 2 : 1;
    }

    /**
     * Returns the maximum disk budget (in megabytes) for the disk-backed replay
     * buffer. Disk is far cheaper than RAM, so the buffer can afford a much
     * larger budget than the old memory-based implementation.
     *
     * <p>Mobile devices get up to 2048 MB (2 GB); desktop platforms get up to
     * 8192 MB (8 GB). The budget is further clamped at runtime to the actual
     * free space on the output volume (see {@link #getFreeDiskSpaceBytes}).</p>
     */
    public static long getReplayBufferDiskBudgetMB() {
        return isMobileDevice() ? 2048L : 8192L;
    }

    /**
     * Returns the free disk space (in bytes) available on the volume that
     * contains the given directory, or {@code -1} if it cannot be determined.
     */
    public static long getFreeDiskSpaceBytes(java.nio.file.Path dir) {
        try {
            java.nio.file.Path probe = dir;
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) return -1L;
            return Files.getFileStore(probe).getUsableSpace();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Returns a user-friendly platform status string for the settings screen.
     */
    public static String getPlatformStatusText() {
        Platform platform = detectPlatform();
        boolean ffmpegFound = isFfmpegAvailable();

        if (platform == Platform.ANDROID) {
            boolean bundled = FfmpegBundleManager.isBundledFfmpegAvailable();
            String arch = AndroidPlatform.detectArchitecture().abi();
            String launcher = AndroidPlatform.detectLauncher().displayName();
            if (bundled) {
                return "✓ Android / " + launcher + " (" + arch + ") - FFmpeg ready";
            }
            return "⚠ Android / " + launcher + " (" + arch + ") - FFmpeg not installed (use Termux: pkg install ffmpeg)";
        }
        if (!ffmpegFound) {
            return "✗ FFmpeg not found - " + getFfmpegInstallHint();
        }
        return "✓ " + platform.displayName() + " - Audio: " + getAudioMethodDescription();
    }

    /**
     * Compresses a video file to save storage space (Android feature).
     *
     * <p>This creates a new compressed version of the video at ~50% of the original size,
     * targeting 720p resolution and a higher CRF value. The original file is kept intact.</p>
     *
     * <p>Requires FFmpeg to be available. Returns the path to the compressed file on success.</p>
     *
     * @param videoPath path to the original video file
     * @return path to the compressed video file, or null if compression failed
     */
    public static Path compressVideoForMobile(Path videoPath) {
        if (!Files.isRegularFile(videoPath)) {
            return null;
        }

        try {
            String fileName = videoPath.getFileName().toString();
            String nameWithoutExt = stripExtension(fileName);
            Path compressedPath = videoPath.getParent().resolve(nameWithoutExt + "_compressed.mp4");

            // Resolve the FFmpeg binary first. If it is unavailable, abort cleanly instead
            // of adding a null argument to the command (which would NPE).
            FFmpegEncoder.FfmpegStatus ffmpegStatus = FFmpegEncoder.detectFfmpeg();
            if (!ffmpegStatus.found() || ffmpegStatus.executable() == null) {
                RecordableMod.LOGGER.warn("[VideoCompression] FFmpeg not available; skipping compression of {}",
                        fileName);
                return null;
            }

            // Build FFmpeg command for mobile-friendly compression
            // Target: <=720p, CRF 28, fast preset, reduced audio bitrate
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegStatus.executable());
            cmd.add("-i");
            cmd.add(videoPath.toAbsolutePath().toString());
            cmd.add("-vcodec");
            cmd.add("libx264");
            cmd.add("-crf");
            cmd.add("28");
            cmd.add("-preset");
            cmd.add("fast");
            cmd.add("-vf");
            // Cap height at 720p but never upscale a smaller clip (which would inflate the file).
            // min(720,ih) keeps the original height when it is already <=720. The comma inside the
            // expression is backslash-escaped because this is passed as a single filtergraph token
            // (no shell), and an unescaped comma would be read as a filter separator.
            cmd.add("scale=-2:min(720\\,ih)");
            cmd.add("-acodec");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("96k");  // Reduced audio bitrate
            cmd.add("-movflags");
            cmd.add("+faststart");  // Optimize for streaming/sharing
            cmd.add("-y");  // Overwrite if exists
            cmd.add(compressedPath.toAbsolutePath().toString());

            RecordableMod.LOGGER.info("[VideoCompression] Starting compression: {} -> {}",
                    fileName, compressedPath.getFileName());

            // Route through FfmpegBundleManager so the Android linker workaround
            // (LD_LIBRARY_PATH + /system/bin/linker64) is applied. On desktop this is a no-op.
            ProcessBuilder pb = FfmpegBundleManager.ffmpegProcess(cmd);
            // FFmpeg writes continuous progress to stderr. If we merge it into stdout and never
            // drain it, the OS pipe buffer (~64KB) fills and the process deadlocks until timeout.
            // Discarding both streams avoids that without spawning a reader thread.
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            
            // Wait up to 5 minutes for compression (should be enough for most videos)
            boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                RecordableMod.LOGGER.warn("[VideoCompression] Compression timed out for {}", fileName);
                return null;
            }

            if (proc.exitValue() == 0 && Files.exists(compressedPath)) {
                long originalBytes = Files.size(videoPath);
                long compressedBytes = Files.size(compressedPath);
                long originalMB = originalBytes / (1024 * 1024);
                long compressedMB = compressedBytes / (1024 * 1024);
                // Guard against divide-by-zero for tiny clips (< 1 byte should never happen, but be safe).
                int savingsPercent = originalBytes > 0
                        ? (int) (100 - (compressedBytes * 100 / originalBytes))
                        : 0;
                RecordableMod.LOGGER.info("[VideoCompression] Compressed {} MB -> {} MB ({}% smaller)",
                        originalMB, compressedMB, savingsPercent);
                return compressedPath;
            }

            RecordableMod.LOGGER.warn("[VideoCompression] Compression failed for {}", fileName);
            return null;

        } catch (Exception e) {
            RecordableMod.LOGGER.warn("[VideoCompression] Error compressing video: {}", e.toString());
            return null;
        }
    }
}
