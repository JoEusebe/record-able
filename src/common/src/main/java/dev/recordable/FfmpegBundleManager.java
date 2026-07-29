package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages FFmpeg detection and on-demand download for the lite/Modrinth-friendly
 * distribution of Record-able.
 *
 * <h3>Why "Lite"?</h3>
 * <p>Modrinth's content rules require mods to either be small, generally MIT-licensed
 * binaries or to disclose third-party redistribution. To stay clean we ship a JAR
 * with no FFmpeg binary inside it (~5 MB) and download an official, trusted build
 * the first time the user records.</p>
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>User-configured path ({@code config.ffmpegPath} or {@code RECORDABLE_FFMPEG_PATH}
 *       env var) - handled by {@link FFmpegEncoder#detectFfmpeg()}.</li>
 *   <li>Locally-downloaded FFmpeg at
 *       {@code <gameDir>/recordable/ffmpeg/bin/ffmpeg[.exe]}.</li>
 *   <li>System {@code PATH} (let the OS resolve {@code ffmpeg}).</li>
 *   <li>Auto-download from a trusted upstream (gyan.dev, johnvansickle.com,
 *       evermeet.cx) when the user clicks "Download FFmpeg" or starts a recording
 *       and FFmpeg is missing.</li>
 * </ol>
 *
 * <h3>Trusted upstreams</h3>
 * <ul>
 *   <li><b>Windows x64</b>: {@code https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip}
 *       (the canonical "release essentials" build maintained by Gyan Doshi for the
 *       FFmpeg project).</li>
 *   <li><b>Linux x64</b>: {@code https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz}
 *       (the canonical Linux static build maintained by John Van Sickle).</li>
 *   <li><b>macOS x64</b>: {@code https://evermeet.cx/ffmpeg/get/zip} (the canonical
 *       static macOS build maintained by Helmut K. C. Tessarek / evermeet.cx).</li>
 *   <li><b>Android (arm64-v8a)</b>: We auto-download a standalone arm64 FFmpeg
 *       ELF executable from {@code hzw1199/Android-FFmpeg-Prebuilt} (FFmpeg 8.0.1,
 *       NDK r28, MediaCodec-enabled). Because the upstream publishes no checksums,
 *       the SHA-256 of the exact binary is hard-coded. The binary is installed into
 *       the first exec-capable directory (POJAV_NATIVEDIR / java.io.tmpdir / app
 *       dirs) that actually permits execution under Android's W^X / SELinux
 *       constraints - verified by running {@code ffmpeg -version} in place.
 *       Non-arm64 Android devices fall back to manual install via
 *       {@link #getAndroidManualInstructions()}.</li>
 * </ul>
 *
 * <h3>Integrity</h3>
 * <p>For Windows builds we fetch the upstream SHA-256 from
 * {@code https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256}
 * and compare it to the downloaded archive. For Linux we hash the archive and
 * compare against the published MD5 (gyan.dev publishes SHA-256, Van Sickle
 * publishes MD5; we use whatever the upstream provides and degrade gracefully
 * if the integrity file is unreachable, logging a warning but still allowing
 * use since the underlying TLS connection authenticates the host). The hash
 * file is fetched over HTTPS from the same host as the binary.</p>
 *
 * <h3>Thread-safety & UI integration</h3>
 * <p>The download runs on a background thread. UI screens (see
 * {@code FfmpegDownloadScreen}) subscribe a {@link ProgressListener} to render
 * a progress bar. State is exposed via {@link #getStatus()}.</p>
 */
public final class FfmpegBundleManager {

    /** Sub-directory under the game directory where downloaded FFmpeg lives. */
    private static final String BUNDLE_DIR = "recordable/ffmpeg";

    // ---- Trusted upstream URLs (HTTPS only) ----
    private static final String WIN_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private static final String WIN_SHA_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256";
    private static final String LINUX_URL =
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
    private static final String LINUX_MD5_URL =
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz.md5";
    // macOS Intel (x86_64): evermeet.cx static build (Helmut K. C. Tessarek).
    private static final String MACOS_INTEL_URL =
            "https://evermeet.cx/ffmpeg/get/zip";
    // macOS Apple Silicon (arm64): Martin Riedl's signed+notarized static builds.
    // The /redirect/latest/... endpoint 307-redirects to the current release zip,
    // each of which contains a single bare binary (ffmpeg / ffprobe).
    private static final String MACOS_ARM_FFMPEG_URL =
            "https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffmpeg.zip";
    private static final String MACOS_ARM_FFPROBE_URL =
            "https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffprobe.zip";

    // ---- Android (arm64-v8a) prebuilt binaries ----
    //
    // Source: hzw1199/Android-FFmpeg-Prebuilt - standalone FFmpeg/ffprobe ELF
    // executables compiled with NDK r28 for arm64-v8a (16 KB page size), with
    // MediaCodec hardware acceleration. The repo only publishes arm64-v8a builds,
    // so armeabi-v7a / x86 Android devices fall back to manual installation.
    //
    // The upstream provides NO checksums, so we pin a known-good version (8.0.1)
    // and hard-code the SHA-256 we computed from the exact blobs in the repo tree.
    // The binaries are served as raw blobs over HTTPS (raw.githubusercontent.com),
    // which authenticates the host; the pinned SHA-256 then authenticates content.
    private static final String ANDROID_FFMPEG_VERSION = "8.0.1";
    private static final String ANDROID_ARM64_FFMPEG_URL =
            "https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/main/ffmpeg-8.0.1/bin/ffmpeg";
    private static final String ANDROID_ARM64_FFPROBE_URL =
            "https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/main/ffmpeg-8.0.1/bin/ffprobe";
    /** SHA-256 of ffmpeg-8.0.1/bin/ffmpeg (arm64-v8a). Hard-coded - upstream has no checksums. */
    private static final String ANDROID_ARM64_FFMPEG_SHA256 =
            "206e84cd597408bbfaf51e27c14b17085590e639e40d568c28735077a6b708b3";
    /** SHA-256 of ffmpeg-8.0.1/bin/ffprobe (arm64-v8a). Hard-coded - upstream has no checksums. */
    private static final String ANDROID_ARM64_FFPROBE_SHA256 =
            "42d18430d4a8b5e6efaf135faa4122a13087ac4a2d20933b81a2b781da07d9d0";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** High-level state for UI binding. */
    public enum Status {
        /** FFmpeg has not been located. */
        NOT_FOUND,
        /** Download is currently in progress. */
        DOWNLOADING,
        /** Download/extraction failed. See {@link #getLastError()}. */
        ERROR,
        /** FFmpeg is available and executable. */
        AVAILABLE
    }

    /**
     * How the resolved FFmpeg binary must be launched on this device.
     *
     * <p>On Android 10+ (API 29+), a binary copied into the app's own storage
     * usually cannot be started with a normal {@code execve()} - SELinux denies
     * the transition and you get {@code error=13, Permission denied}. However the
     * very same file can still be loaded with execute permission by the system
     * dynamic linker ({@code /system/bin/linker64}), because that uses the
     * {@code mmap(PROT_EXEC)} / {@code execmod} path that apps ARE allowed to use
     * (it is exactly how launchers like Pojav/Zalith load their own Java runtime
     * from app storage). So when direct exec fails we retry through the linker and
     * remember which method actually worked, then reuse it for real recording.</p>
     */
    public enum ExecMethod {
        /** Run the binary directly: {@code [path, args...]} (desktop + lucky Android). */
        DIRECT,
        /** Run via the 64-bit dynamic linker: {@code [/system/bin/linker64, path, args...]}. */
        LINKER64,
        /** Run via the 32-bit dynamic linker: {@code [/system/bin/linker, path, args...]}. */
        LINKER32,
        /** Run through the system shell: {@code [/system/bin/sh, -c, "'path' args"]}. */
        SHELL
    }

    /** 64-bit Android dynamic linker (arm64-v8a / x86_64). */
    private static final String LINKER64 = "/system/bin/linker64";
    /** 32-bit Android dynamic linker (armeabi-v7a / x86). */
    private static final String LINKER32 = "/system/bin/linker";

    /** Execution method verified to work for the currently-resolved binary. */
    private static volatile ExecMethod cachedExecMethod = ExecMethod.DIRECT;

    private static volatile String cachedPath;

    /**
     * Cached FFmpeg major version (e.g., 5 for FFmpeg 5.1.2, 4 for FFmpeg 4.4.2).
     * Zero means version could not be determined. Used to select compatible CLI flags
     * (-fps_mode for FFmpeg 5.1+, -vsync for older versions).
     */
    private static volatile int cachedMajorVersion = 0;
    private static volatile boolean checkedOnce;
    private static final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_FOUND);
    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static volatile String lastError;
    private static volatile DownloadProgress lastProgress = DownloadProgress.IDLE;

    private static final List<ProgressListener> listeners = new ArrayList<>();

    private FfmpegBundleManager() {
    }

    /**
     * Returns the absolute path to a usable FFmpeg executable, or {@code null}
     * if none has been downloaded/found yet.
     *
     * <p>Only checks the locally-downloaded location and verifies it can be
     * executed. System PATH and user-configured paths are resolved by
     * {@link FFmpegEncoder#detectFfmpeg()} which calls this method first.</p>
     */
    public static String getBundledFfmpegPath() {
        if (checkedOnce) {
            return cachedPath;
        }
        synchronized (FfmpegBundleManager.class) {
            if (checkedOnce) {
                return cachedPath;
            }
            cachedPath = resolveLocal();
            checkedOnce = true;
            if (cachedPath != null) {
                status.set(Status.AVAILABLE);
            }
            return cachedPath;
        }
    }

    /** @return {@code true} if a downloaded FFmpeg is present and executable. */
    public static boolean isBundledFfmpegAvailable() {
        return getBundledFfmpegPath() != null;
    }

    /** Forces the next call to re-probe the filesystem. */
    public static void invalidateCache() {
        synchronized (FfmpegBundleManager.class) {
            cachedPath = null;
            checkedOnce = false;
            cachedExecMethod = ExecMethod.DIRECT;
        }
    }

    /** Directory where downloaded FFmpeg binaries live: {@code <gameDir>/recordable/ffmpeg/bin}. */
    public static Path getBundleDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(BUNDLE_DIR).resolve("bin");
    }

    /** Current status of the FFmpeg bundle. */
    public static Status getStatus() {
        // refresh from cache if available
        if (status.get() != Status.DOWNLOADING && isBundledFfmpegAvailable()) {
            status.set(Status.AVAILABLE);
        }
        return status.get();
    }

    /** Most recent error message produced by a failed download or extraction. */
    public static String getLastError() {
        return lastError;
    }

    /** Most recent progress snapshot (bytes downloaded, total, phase). */
    public static DownloadProgress getLastProgress() {
        return lastProgress;
    }

    /** True while a background download is in flight. */
    public static boolean isDownloading() {
        return downloading.get();
    }

    /** Register a progress listener. Safe to call from any thread. */
    public static void addProgressListener(ProgressListener listener) {
        if (listener == null) return;
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeProgressListener(ProgressListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private static void fireProgress(DownloadProgress p) {
        lastProgress = p;
        List<ProgressListener> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (ProgressListener l : snapshot) {
            try {
                l.onProgress(p);
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("[FfmpegBundle] Listener threw: {}", t.getMessage());
            }
        }
    }

    /** Snapshot of download progress, used by listeners. */
    public record DownloadProgress(String phase, long bytesDownloaded, long totalBytes, double fraction) {
        public static final DownloadProgress IDLE = new DownloadProgress("idle", 0L, 0L, 0.0);

        public String displayPercent() {
            if (totalBytes <= 0) {
                return bytesDownloaded > 0 ? humanBytes(bytesDownloaded) : "0%";
            }
            return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
        }

        public String displayBytes() {
            if (totalBytes <= 0) return humanBytes(bytesDownloaded);
            return humanBytes(bytesDownloaded) + " / " + humanBytes(totalBytes);
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /** Callback for download progress updates. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private static String resolveLocal() {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        RecordableMod.LOGGER.info("[FfmpegBundle] Resolving local FFmpeg (lite mode)");
        RecordableMod.LOGGER.info("[FfmpegBundle]   Platform: {}", platform.displayName());
        RecordableMod.LOGGER.info("[FfmpegBundle]   Bundle dir: {}", getBundleDirectory());

        // On Android, prefer a Termux-installed ffmpeg before anything else: its
        // /data/data/com.termux/files/usr/bin prefix is exec-capable, so it works
        // even when the launcher's own data dirs are noexec/SELinux-blocked.
        if (platform == PlatformUtils.Platform.ANDROID) {
            Path termux = AndroidPlatform.getTermuxFfmpegPath();
            if (termux != null) {
                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] Found Termux ffmpeg at {}", termux);
                if (!Files.isExecutable(termux)) {
                    setExecutablePermission(termux);
                }
                if (verifyBinaryExecution(termux)) {
                    RecordableMod.LOGGER.info("[FfmpegBundle] [Android] ✓ Using Termux ffmpeg: {}", termux);
                    return termux.toAbsolutePath().toString();
                }
                RecordableMod.LOGGER.warn("[FfmpegBundle] [Android] Termux ffmpeg present but not runnable "
                        + "(is Termux's storage accessible to this launcher?): {}", termux);
            }
        }

        Path candidate = getBundleDirectory().resolve(getExecutableName());
        if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
            if (!PlatformUtils.isWindows() && !Files.isExecutable(candidate)) {
                setExecutablePermission(candidate);
            }
            if (verifyBinaryExecution(candidate)) {
                RecordableMod.LOGGER.info("[FfmpegBundle] ✓ Found downloaded FFmpeg: {}", candidate);
                return candidate.toAbsolutePath().toString();
            }
            RecordableMod.LOGGER.warn("[FfmpegBundle] Found {} but cannot execute it (noexec/permission?)", candidate);
        } else {
            RecordableMod.LOGGER.info("[FfmpegBundle] No downloaded FFmpeg at {}", candidate);
        }

        // On Android, also check a small fallback of exec-capable paths.
        if (platform == PlatformUtils.Platform.ANDROID) {
            Path execPath = findOnAndroidExecCapablePaths();
            if (execPath != null) {
                return execPath.toAbsolutePath().toString();
            }
        }

        return null;
    }

    private static boolean verifyBinaryExecution(Path binary) {
        String path = binary.toAbsolutePath().toString();

        // Attempt 1: direct execution. Works on desktop and on Android devices
        // that do not enforce the W^X / SELinux exec restriction for this process.
        if (runVersionProbe(new String[]{path, "-version"}, binary, ExecMethod.DIRECT)) {
            cachedExecMethod = ExecMethod.DIRECT;
            return true;
        }

        if (PlatformUtils.isAndroid()) {
            // Attempt 2: run through the system dynamic linker. On Android 10+ a
            // direct execve() of an app-storage binary is denied (error=13), but
            // loading it through linker64 uses the mmap(PROT_EXEC)/execmod path
            // that the app IS allowed to use - the same mechanism the launcher
            // uses to run its own Java runtime from app storage. This is the most
            // important Android workaround.
            if (Files.isRegularFile(Path.of(LINKER64))
                    && runVersionProbe(new String[]{LINKER64, path, "-version"}, binary, ExecMethod.LINKER64)) {
                cachedExecMethod = ExecMethod.LINKER64;
                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] {} runs via linker64 (direct exec blocked)", binary);
                return true;
            }
            if (Files.isRegularFile(Path.of(LINKER32))
                    && runVersionProbe(new String[]{LINKER32, path, "-version"}, binary, ExecMethod.LINKER32)) {
                cachedExecMethod = ExecMethod.LINKER32;
                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] {} runs via linker (direct exec blocked)", binary);
                return true;
            }

            // Attempt 3: system shell wrapper (rarely helps, kept as a last resort).
            String quoted = "'" + path.replace("'", "'\\''") + "'";
            if (runVersionProbe(new String[]{"/system/bin/sh", "-c", quoted + " -version"}, binary, ExecMethod.SHELL)) {
                cachedExecMethod = ExecMethod.SHELL;
                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] {} runs via shell wrapper (direct exec blocked)",
                        binary);
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a {@code -version} probe with the given command and returns {@code true}
     * if it exits 0 and the output mentions ffmpeg. Failures are logged at debug.
     *
     * @param method the execution method being probed - used to set
     *               {@code LD_LIBRARY_PATH} so a linker-loaded dynamic binary can
     *               find any co-located shared libraries.
     */
    private static boolean runVersionProbe(String[] command, Path binary, ExecMethod method) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            applyExecEnv(pb, binary.toAbsolutePath().toString(), method);
            Process proc = pb.start();
            boolean exited = proc.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return false;
            }
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return proc.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("ffmpeg");
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] Execution check failed for {} via {}: {}",
                    binary, command.length > 0 ? command[0] : "?", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Execution-method aware command building (Android W^X workaround)
    // ------------------------------------------------------------------

    /** @return the execution method verified to work for the resolved FFmpeg binary. */
    public static ExecMethod getExecMethod() {
        return cachedExecMethod;
    }

    /**
     * Returns the FFmpeg major version (e.g., 5 for FFmpeg 5.1.2, 4 for FFmpeg 4.4.2,
     * or 0 if version could not be determined). This is used to select compatible CLI
     * flags: FFmpeg 5.1+ uses {@code -fps_mode cfr}, older versions use {@code -vsync 1}.
     *
     * <p>The version is detected by parsing {@code ffmpeg -version} output once and
     * cached. If FFmpeg is not yet resolved, this returns 0.</p>
     */
    public static int getMajorVersion() {
        if (cachedMajorVersion == 0 && cachedPath != null) {
            cachedMajorVersion = detectMajorVersion();
        }
        return cachedMajorVersion;
    }

    /**
     * Detects the FFmpeg major version by running {@code ffmpeg -version} and parsing
     * the first line. Returns 0 if detection fails.
     *
     * <p>Handles various version formats:
     * <ul>
     *   <li>{@code ffmpeg version 6.0.1} (release) → 6</li>
     *   <li>{@code ffmpeg version 4.4.2-0ubuntu0.22.04.1} (distro package) → 4</li>
     *   <li>{@code ffmpeg version N-55702-g920046a} (ancient git snapshot) → 0 (unknown)</li>
     * </ul>
     */
    private static int detectMajorVersion() {
        if (cachedPath == null) {
            return 0;
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(cachedPath);
            command.add("-version");
            command = wrapCommandForExec(command);

            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            applyExecEnv(pb, cachedPath, cachedExecMethod);
            Process proc = pb.start();
            boolean exited = proc.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return 0;
            }
            if (proc.exitValue() != 0) {
                return 0;
            }

            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // First line: "ffmpeg version 6.0.1 Copyright..." or "ffmpeg version N-55702-g920046a..."
            String firstLine = output.lines().findFirst().orElse("");
            // Extract the version token (third word)
            String[] parts = firstLine.split("\\s+");
            if (parts.length < 3) {
                return 0;
            }
            String versionToken = parts[2]; // e.g., "6.0.1" or "4.4.2-0ubuntu0.22.04.1" or "N-55702-g920046a"

            // If it starts with "N-" it's a git snapshot (very old), treat as unknown
            if (versionToken.startsWith("N-") || versionToken.startsWith("n-")) {
                RecordableMod.LOGGER.debug("[FfmpegBundle] FFmpeg git snapshot version detected ({}), treating as legacy (pre-5.1)", versionToken);
                return 0; // treat ancient snapshots as pre-5.1
            }

            // Parse the major version (first numeric part before "." or "-")
            int dotIndex = versionToken.indexOf('.');
            int dashIndex = versionToken.indexOf('-');
            int endIndex = -1;
            if (dotIndex > 0 && dashIndex > 0) {
                endIndex = Math.min(dotIndex, dashIndex);
            } else if (dotIndex > 0) {
                endIndex = dotIndex;
            } else if (dashIndex > 0) {
                endIndex = dashIndex;
            }

            String majorStr = endIndex > 0 ? versionToken.substring(0, endIndex) : versionToken;
            int major = Integer.parseInt(majorStr);
            RecordableMod.LOGGER.info("[FfmpegBundle] Detected FFmpeg major version: {} (from: {})", major, versionToken);
            return major;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] Failed to detect FFmpeg version: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Wraps a command list (whose first element is the absolute path to the
     * ffmpeg/ffprobe binary) so it launches using the execution method that was
     * verified to work on this device. On desktop, and whenever the method is
     * {@link ExecMethod#DIRECT}, the command is returned unchanged.
     *
     * <p>Callers should also pass the resulting {@link ProcessBuilder} through
     * {@link #applyExecEnv(ProcessBuilder, String)} so a linker-loaded binary can
     * resolve its shared libraries.</p>
     */
    public static List<String> wrapCommandForExec(List<String> command) {
        if (command == null || command.isEmpty() || cachedExecMethod == ExecMethod.DIRECT) {
            return command;
        }
        switch (cachedExecMethod) {
            case LINKER64: {
                List<String> wrapped = new ArrayList<>(command.size() + 1);
                wrapped.add(LINKER64);
                wrapped.addAll(command);
                return wrapped;
            }
            case LINKER32: {
                List<String> wrapped = new ArrayList<>(command.size() + 1);
                wrapped.add(LINKER32);
                wrapped.addAll(command);
                return wrapped;
            }
            case SHELL: {
                // Quote every token for /system/bin/sh -c. stdin (pipe:0) is still
                // inherited from the ProcessBuilder, so raw-video streaming works.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < command.size(); i++) {
                    if (i > 0) sb.append(' ');
                    sb.append('\'').append(command.get(i).replace("'", "'\\''")).append('\'');
                }
                List<String> wrapped = new ArrayList<>(3);
                wrapped.add("/system/bin/sh");
                wrapped.add("-c");
                wrapped.add(sb.toString());
                return wrapped;
            }
            default:
                return command;
        }
    }

    /**
     * Sets {@code LD_LIBRARY_PATH} on the given builder so a binary launched via
     * the dynamic linker can find shared libraries that sit next to it. No-op for
     * {@link ExecMethod#DIRECT} / {@link ExecMethod#SHELL}.
     */
    public static void applyExecEnv(ProcessBuilder pb, String binaryPath) {
        applyExecEnv(pb, binaryPath, cachedExecMethod);
    }

    /**
     * Builds a {@link ProcessBuilder} for an ffmpeg/ffprobe invocation, transparently
     * applying the device-specific execution workaround (e.g. launching via
     * {@code /system/bin/linker64} on Android, where directly executing a binary in
     * app storage is blocked by SELinux). On desktop this is a no-op passthrough.
     *
     * <p>The first element of {@code command} must be the absolute path to the
     * ffmpeg/ffprobe binary. This is the single entry point every ad-hoc ffmpeg
     * invocation should use so the linker workaround is applied consistently.</p>
     */
    public static ProcessBuilder ffmpegProcess(List<String> command) {
        List<String> wrapped = wrapCommandForExec(command);
        ProcessBuilder pb = new ProcessBuilder(wrapped);
        if (command != null && !command.isEmpty()) {
            applyExecEnv(pb, command.get(0));
        }
        return pb;
    }

    /** Varargs convenience overload of {@link #ffmpegProcess(List)}. */
    public static ProcessBuilder ffmpegProcess(String... command) {
        return ffmpegProcess(new ArrayList<>(Arrays.asList(command)));
    }

    private static void applyExecEnv(ProcessBuilder pb, String binaryPath, ExecMethod method) {
        if (pb == null || binaryPath == null
                || (method != ExecMethod.LINKER64 && method != ExecMethod.LINKER32)) {
            return;
        }
        try {
            Path parent = Path.of(binaryPath).toAbsolutePath().getParent();
            if (parent == null) {
                return;
            }
            String add = parent.toString();
            String existing = pb.environment().get("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    (existing == null || existing.isBlank()) ? add : add + ":" + existing);
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] Could not set LD_LIBRARY_PATH for {}: {}",
                    binaryPath, e.getMessage());
        }
    }

    /**
     * Scans Android exec-capable directories (resolved by {@link AndroidPlatform})
     * for a previously-installed FFmpeg binary, accepting either the launcher-
     * friendly {@code recordable-ffmpeg} name or a plain {@code ffmpeg}.
     */
    private static Path findOnAndroidExecCapablePaths() {
        String[] binaryNames = { AndroidPlatform.FFMPEG_EXEC_NAME, "ffmpeg" };
        for (Path dir : AndroidPlatform.getExecCapableDirectories()) {
            for (String binaryName : binaryNames) {
                Path candidate = dir.resolve(binaryName);
                if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                    if (!Files.isExecutable(candidate)) {
                        setExecutablePermission(candidate);
                    }
                    if (verifyBinaryExecution(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** @deprecated use {@link AndroidPlatform#getPackageName()}. Kept for API stability. */
    @Deprecated
    static String detectAndroidPackageName() {
        return AndroidPlatform.getPackageName();
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if auto-download is supported for the current platform.
     *
     * <p>Desktop platforms (Windows/Linux/macOS) are always supported. Android is
     * supported only on {@code arm64-v8a} devices, because the upstream
     * (hzw1199/Android-FFmpeg-Prebuilt) only publishes arm64 builds and because
     * 32-bit/x86 Android setups are vanishingly rare for modded Minecraft.</p>
     */
    public static boolean isAutoDownloadSupported() {
        PlatformUtils.Platform p = PlatformUtils.detectPlatform();
        if (p == PlatformUtils.Platform.ANDROID) {
            // Only arm64-v8a has a prebuilt binary available upstream.
            return AndroidPlatform.isArm64();
        }
        return p == PlatformUtils.Platform.WINDOWS
                || p == PlatformUtils.Platform.LINUX
                || p == PlatformUtils.Platform.MACOS;
    }

    /** Human-readable upstream description for the current platform. */
    public static String getDownloadSourceDescription() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "gyan.dev (FFmpeg release essentials, Windows x64)";
            case LINUX -> "johnvansickle.com (FFmpeg release static, Linux x64)";
            case MACOS -> PlatformUtils.isMacArm()
                    ? "ffmpeg.martin-riedl.de (FFmpeg static, macOS arm64 / Apple Silicon)"
                    : "evermeet.cx (FFmpeg static, macOS x86_64 / Intel)";
            case ANDROID -> AndroidPlatform.isArm64()
                    ? "hzw1199/Android-FFmpeg-Prebuilt (FFmpeg " + ANDROID_FFMPEG_VERSION + ", arm64-v8a)"
                    : "Not supported on " + AndroidPlatform.detectArchitecture().abi()
                            + " - see manual instructions";
            case UNKNOWN -> "Unsupported platform";
        };
    }

    /** Approximate download size text for the UI. */
    public static String getEstimatedDownloadSize() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "~103 MB";
            case LINUX -> "~80 MB";
            case MACOS -> "~80 MB";
            case ANDROID -> "~30 MB";
            default -> "n/a";
        };
    }

    /**
     * Triggers an asynchronous download + extraction of FFmpeg. Safe to call multiple
     * times - subsequent calls while a download is in flight return the same future.
     *
     * @param onComplete callback invoked on completion with success/failure flag
     * @return a {@link CompletableFuture} resolving to {@code true} if FFmpeg is
     *         available after the call, or {@code false} on failure
     */
    public static CompletableFuture<Boolean> downloadAsync(Consumer<Boolean> onComplete) {
        if (!isAutoDownloadSupported()) {
            String err = "Auto-download is not supported on " + PlatformUtils.detectPlatform().displayName()
                    + ". See manual installation instructions.";
            lastError = err;
            status.set(Status.ERROR);
            RecordableMod.LOGGER.warn("[FfmpegBundle] {}", err);
            if (onComplete != null) onComplete.accept(false);
            return CompletableFuture.completedFuture(false);
        }
        if (!downloading.compareAndSet(false, true)) {
            RecordableMod.LOGGER.info("[FfmpegBundle] Download already in progress, ignoring duplicate request");
            CompletableFuture<Boolean> existing = new CompletableFuture<>();
            existing.complete(false);
            return existing;
        }

        status.set(Status.DOWNLOADING);
        lastError = null;
        fireProgress(new DownloadProgress("starting", 0, 0, 0.0));

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                doDownloadAndInstall();
                invalidateCache();
                String resolved = getBundledFfmpegPath();
                boolean ok = resolved != null;
                if (ok) {
                    status.set(Status.AVAILABLE);
                    fireProgress(new DownloadProgress("done", 1, 1, 1.0));
                    RecordableMod.LOGGER.info("[FfmpegBundle] ✓ FFmpeg ready at {}", resolved);
                } else {
                    status.set(Status.ERROR);
                    lastError = "Download finished but FFmpeg binary could not be located after extraction.";
                    fireProgress(new DownloadProgress("error", 0, 0, 0.0));
                }
                return ok;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                status.set(Status.ERROR);
                fireProgress(new DownloadProgress("error", 0, 0, 0.0));
                RecordableMod.LOGGER.warn("[FfmpegBundle] Download failed: {}", lastError, e);
                return false;
            } finally {
                downloading.set(false);
            }
        });

        if (onComplete != null) {
            future.whenComplete((ok, ex) -> {
                try {
                    onComplete.accept(ok != null && ok);
                } catch (Throwable t) {
                    RecordableMod.LOGGER.warn("[FfmpegBundle] onComplete threw: {}", t.getMessage());
                }
            });
        }
        return future;
    }

    private static void doDownloadAndInstall() throws IOException {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        Path bundleDir = getBundleDirectory();
        Files.createDirectories(bundleDir);

        Path tempDir = Files.createTempDirectory("recordable-ffmpeg-");
        try {
            switch (platform) {
                case WINDOWS -> downloadAndInstallWindows(tempDir, bundleDir);
                case LINUX -> downloadAndInstallLinux(tempDir, bundleDir);
                case MACOS -> downloadAndInstallMacOS(tempDir, bundleDir);
                case ANDROID -> downloadAndInstallAndroid(tempDir);
                default -> throw new IOException("Platform not supported for auto-download: " + platform.displayName());
            }
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static void downloadAndInstallWindows(Path tempDir, Path bundleDir) throws IOException {
        Path zipFile = tempDir.resolve("ffmpeg.zip");
        downloadFile(WIN_URL, zipFile, "Downloading FFmpeg (Windows)");

        // Verify SHA-256 against gyan.dev's published file (best-effort).
        String expected = tryFetchExpectedHash(WIN_SHA_URL, "SHA-256");
        if (expected != null) {
            String actual = computeSha256(zipFile);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch for FFmpeg download. "
                        + "expected=" + expected + ", actual=" + actual);
            }
            RecordableMod.LOGGER.info("[FfmpegBundle] ✓ SHA-256 verified against gyan.dev");
        } else {
            RecordableMod.LOGGER.warn("[FfmpegBundle] Could not fetch upstream SHA-256, "
                    + "relying on HTTPS authentication only.");
        }

        fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
        extractZipFindExecutable(zipFile, bundleDir, "ffmpeg.exe");
    }

    private static void downloadAndInstallLinux(Path tempDir, Path bundleDir) throws IOException {
        Path archive = tempDir.resolve("ffmpeg.tar.xz");
        downloadFile(LINUX_URL, archive, "Downloading FFmpeg (Linux)");

        String expectedMd5 = tryFetchExpectedHash(LINUX_MD5_URL, "MD5");
        if (expectedMd5 != null) {
            String actual = computeMd5(archive);
            if (!expectedMd5.equalsIgnoreCase(actual)) {
                throw new IOException("MD5 mismatch for FFmpeg download. "
                        + "expected=" + expectedMd5 + ", actual=" + actual);
            }
            RecordableMod.LOGGER.info("[FfmpegBundle] ✓ MD5 verified against johnvansickle.com");
        } else {
            RecordableMod.LOGGER.warn("[FfmpegBundle] Could not fetch upstream MD5, "
                    + "relying on HTTPS authentication only.");
        }

        fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
        // Use system tar to unpack the .tar.xz (always present on Linux + macOS).
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        runProcess(new String[]{"tar", "-xJf", archive.toAbsolutePath().toString(),
                "-C", extractDir.toAbsolutePath().toString()});
        Path ffmpegBin = findFileRecursive(extractDir, "ffmpeg");
        if (ffmpegBin == null) {
            throw new IOException("Could not locate 'ffmpeg' binary in extracted archive at " + extractDir);
        }
        Path target = bundleDir.resolve("ffmpeg");
        Files.copy(ffmpegBin, target, StandardCopyOption.REPLACE_EXISTING);
        setExecutablePermission(target);

        // Also copy ffprobe if present (useful for VideoMetadata).
        Path ffprobeBin = findFileRecursive(extractDir, "ffprobe");
        if (ffprobeBin != null) {
            Path probeTarget = bundleDir.resolve("ffprobe");
            Files.copy(ffprobeBin, probeTarget, StandardCopyOption.REPLACE_EXISTING);
            setExecutablePermission(probeTarget);
        }
    }

    private static void downloadAndInstallMacOS(Path tempDir, Path bundleDir) throws IOException {
        boolean arm = PlatformUtils.isMacArm();

        if (arm) {
            // Apple Silicon (arm64): evermeet.cx ships only Intel builds, so we use
            // Martin Riedl's signed+notarized arm64 static builds instead. ffmpeg and
            // ffprobe are separate single-binary zips.
            RecordableMod.LOGGER.info("[FfmpegBundle] Apple Silicon (arm64) Mac detected - "
                    + "downloading arm64 FFmpeg from ffmpeg.martin-riedl.de");
            Path zipFile = tempDir.resolve("ffmpeg.zip");
            downloadFile(MACOS_ARM_FFMPEG_URL, zipFile, "Downloading FFmpeg (macOS arm64)");
            fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
            extractZipFindExecutable(zipFile, bundleDir, "ffmpeg");

            // Best-effort ffprobe (used for video metadata); a failure here is non-fatal.
            try {
                Path probeZip = tempDir.resolve("ffprobe.zip");
                downloadFile(MACOS_ARM_FFPROBE_URL, probeZip, "Downloading ffprobe (macOS arm64)");
                extractZipFindExecutable(probeZip, bundleDir, "ffprobe");
            } catch (IOException e) {
                RecordableMod.LOGGER.warn("[FfmpegBundle] Optional ffprobe (arm64) download failed: {}",
                        e.getMessage());
            }
        } else {
            // Intel (x86_64), including a Rosetta 2 JVM on Apple Silicon.
            RecordableMod.LOGGER.info("[FfmpegBundle] Intel (x86_64) Mac detected - "
                    + "downloading Intel FFmpeg from evermeet.cx");
            Path zipFile = tempDir.resolve("ffmpeg.zip");
            downloadFile(MACOS_INTEL_URL, zipFile, "Downloading FFmpeg (macOS x86_64)");

            // evermeet.cx does not publish a stable .sha256 sibling URL. The TLS
            // connection authenticates the host, and the zip is signed (signature
            // available at /sig endpoint), but for simplicity we rely on HTTPS here.
            RecordableMod.LOGGER.info("[FfmpegBundle] HTTPS-authenticated download from evermeet.cx (no sibling hash file)");

            fireProgress(new DownloadProgress("extracting", 0, 1, 0.0));
            extractZipFindExecutable(zipFile, bundleDir, "ffmpeg");
        }
    }

    /**
     * Downloads and installs the Android {@code arm64-v8a} FFmpeg binary.
     *
     * <p>Unlike the desktop flows, the Android upstream serves <i>standalone ELF
     * executables</i> (not archives), so there is nothing to extract. The pipeline is:</p>
     * <ol>
     *   <li>Download {@code ffmpeg} (and best-effort {@code ffprobe}) into the JVM
     *       temp dir and verify each against a hard-coded SHA-256 (upstream publishes
     *       no checksums).</li>
     *   <li>Walk the ordered list of Android exec-capable directories from
     *       {@link AndroidPlatform#getExecCapableDirectories()} (POJAV_NATIVEDIR →
     *       java.io.tmpdir → app dirs). For each, copy the binary in, apply
     *       executable permissions, and actually <i>run</i> {@code ffmpeg -version}
     *       to prove the directory is not {@code noexec}/SELinux-blocked.</li>
     *   <li>The first directory that yields a working binary wins; otherwise we fail
     *       with Android-specific guidance.</li>
     * </ol>
     *
     * <p>This in-place exec verification is the fallback logic for Android's W^X /
     * SELinux constraints - many app-private dirs are mounted {@code noexec}, so we
     * cannot assume the first writable directory is runnable.</p>
     */
    private static void downloadAndInstallAndroid(Path tempDir) throws IOException {
        // If Termux already provides a working ffmpeg, there is nothing to download -
        // it lives in an exec-capable prefix and sidesteps the W^X/noexec problem.
        if (AndroidPlatform.hasTermuxFfmpeg()) {
            Path termux = AndroidPlatform.getTermuxFfmpegPath();
            if (termux != null && verifyBinaryExecution(termux)) {
                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] Termux ffmpeg already usable at {}, "
                        + "skipping download", termux);
                return;
            }
        }

        if (!AndroidPlatform.isArm64()) {
            throw new IOException("Android auto-download only supports arm64-v8a devices "
                    + "(detected " + AndroidPlatform.detectArchitecture().abi() + "). "
                    + getAndroidManualInstructions());
        }

        RecordableMod.LOGGER.info("[FfmpegBundle] [Android] Installing FFmpeg {} (arm64-v8a) from {}",
                ANDROID_FFMPEG_VERSION, "hzw1199/Android-FFmpeg-Prebuilt");
        AndroidPlatform.logDiagnostics();

        // 1. Download + SHA-256 verify the ffmpeg binary (mandatory).
        Path tmpFfmpeg = tempDir.resolve("ffmpeg");
        downloadFile(ANDROID_ARM64_FFMPEG_URL, tmpFfmpeg, "Downloading FFmpeg (Android arm64)");
        verifyHardcodedSha256(tmpFfmpeg, ANDROID_ARM64_FFMPEG_SHA256, "ffmpeg");

        // 2. Download + verify ffprobe (best-effort; recording works without it).
        Path tmpFfprobe = tempDir.resolve("ffprobe");
        boolean haveProbe = false;
        try {
            downloadFile(ANDROID_ARM64_FFPROBE_URL, tmpFfprobe, "Downloading ffprobe (Android arm64)");
            verifyHardcodedSha256(tmpFfprobe, ANDROID_ARM64_FFPROBE_SHA256, "ffprobe");
            haveProbe = true;
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[FfmpegBundle] [Android] ffprobe unavailable, continuing with ffmpeg only: {}",
                    e.getMessage());
        }

        // 3. Try each exec-capable directory until the binary actually runs.
        fireProgress(new DownloadProgress("installing", 0, 1, 0.0));
        List<Path> execDirs = AndroidPlatform.getExecCapableDirectories();
        if (execDirs.isEmpty()) {
            throw new IOException("No candidate exec-capable directories on this device. "
                    + getAndroidManualInstructions());
        }
        RecordableMod.LOGGER.info("[FfmpegBundle] [Android] Will try {} exec-candidate dir(s) in order:",
                execDirs.size());
        for (int i = 0; i < execDirs.size(); i++) {
            RecordableMod.LOGGER.info("[FfmpegBundle] [Android]   {}. {}", i + 1, execDirs.get(i));
        }
        IOException lastFailure = null;
        for (Path dir : execDirs) {
            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(AndroidPlatform.FFMPEG_EXEC_NAME);
                Files.copy(tmpFfmpeg, target, StandardCopyOption.REPLACE_EXISTING);
                applyAndroidExecPermissions(target);

                if (!verifyBinaryExecution(target)) {
                    lastFailure = new IOException("Copied to " + dir
                            + " but could not execute it (noexec mount or SELinux denial).");
                    RecordableMod.LOGGER.warn("[FfmpegBundle] [Android] {}", lastFailure.getMessage());
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignored) {
                    }
                    continue;
                }

                RecordableMod.LOGGER.info("[FfmpegBundle] [Android] ✓ FFmpeg installed and verified executable at {}",
                        target);

                if (haveProbe) {
                    try {
                        Path probeTarget = dir.resolve(AndroidPlatform.FFPROBE_EXEC_NAME);
                        Files.copy(tmpFfprobe, probeTarget, StandardCopyOption.REPLACE_EXISTING);
                        applyAndroidExecPermissions(probeTarget);
                        RecordableMod.LOGGER.info("[FfmpegBundle] [Android] ✓ ffprobe installed at {}", probeTarget);
                    } catch (IOException pe) {
                        RecordableMod.LOGGER.warn("[FfmpegBundle] [Android] Could not install ffprobe at {}: {}",
                                dir, pe.getMessage());
                    }
                }
                return; // success
            } catch (IOException e) {
                lastFailure = e;
                RecordableMod.LOGGER.warn("[FfmpegBundle] [Android] Install into {} failed: {}", dir, e.getMessage());
            }
        }

        throw new IOException("Downloaded FFmpeg but no Android directory permitted execution "
                + "(all candidates were noexec/SELinux-blocked). "
                + (lastFailure != null ? "Last error: " + lastFailure.getMessage() + ". " : "")
                + getAndroidManualInstructions());
    }

    /**
     * Verifies a downloaded file against a hard-coded SHA-256, aborting on mismatch.
     * Used for Android binaries where the upstream publishes no checksum file.
     */
    private static void verifyHardcodedSha256(Path file, String expected, String label) throws IOException {
        String actual = computeSha256(file);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for Android " + label
                    + " - refusing to install. expected=" + expected + ", actual=" + actual);
        }
        RecordableMod.LOGGER.info("[FfmpegBundle] [Android] ✓ SHA-256 verified for {} ({})", label, expected);
    }

    /**
     * Applies execute permission to an Android binary, satisfying W^X by ensuring the
     * file is both readable and executable. Uses the Java {@code File} API first and
     * falls back to {@code chmod +x} via {@link #setExecutablePermission(Path)}.
     */
    private static void applyAndroidExecPermissions(Path path) {
        try {
            java.io.File f = path.toFile();
            f.setReadable(true, false);
            f.setExecutable(true, false);
        } catch (Exception ignored) {
        }
        boolean ok = setExecutablePermission(path);
        RecordableMod.LOGGER.info("[FfmpegBundle] [Android] chmod +x {} → {} (executable={})",
                path, ok ? "ok" : "fell back", Files.isExecutable(path));
    }

    /**
     * Extracts the named executable from a zip and copies it to {@code bundleDir/<exeName>}.
     */
    private static void extractZipFindExecutable(Path zipFile, Path bundleDir, String exeName) throws IOException {
        boolean found = false;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String basename = name.substring(name.lastIndexOf('/') + 1);
                if (basename.equalsIgnoreCase(exeName)) {
                    Path target = bundleDir.resolve(exeName);
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                    setExecutablePermission(target);
                    clearMacOSQuarantine(target);
                    RecordableMod.LOGGER.info("[FfmpegBundle] Extracted {} → {}", name, target);
                    found = true;
                } else if (basename.equalsIgnoreCase("ffprobe.exe") || basename.equalsIgnoreCase("ffprobe")) {
                    // Take ffprobe too if present (used by VideoMetadata).
                    String probeName = PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe";
                    Path target = bundleDir.resolve(probeName);
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                    setExecutablePermission(target);
                    clearMacOSQuarantine(target);
                    RecordableMod.LOGGER.info("[FfmpegBundle] Extracted {} → {}", name, target);
                }
            }
        }
        if (!found) {
            throw new IOException("Could not find '" + exeName + "' inside downloaded archive.");
        }
    }

    private static Path findFileRecursive(Path root, String filename) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(filename))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void runProcess(String[] cmd) throws IOException {
        try {
            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = proc.waitFor(2, TimeUnit.MINUTES);
            if (!done) {
                proc.destroyForcibly();
                throw new IOException("Process timed out: " + String.join(" ", cmd));
            }
            if (proc.exitValue() != 0) {
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Process failed (" + proc.exitValue() + "): "
                        + String.join(" ", cmd) + "\n" + out);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + String.join(" ", cmd), ie);
        }
    }

    /**
     * Streaming HTTPS download with progress reporting. Supports redirects.
     */
    private static void downloadFile(String urlStr, Path target, String phase) throws IOException {
        RecordableMod.LOGGER.info("[FfmpegBundle] Downloading: {}", urlStr);
        HttpURLConnection conn = openConnectionFollowingRedirects(urlStr, 5);
        long contentLength = conn.getContentLengthLong();
        long downloaded = 0;
        long lastReport = 0;

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 200 || (contentLength > 0 && downloaded >= contentLength)) {
                    double frac = contentLength > 0 ? (double) downloaded / contentLength : 0.0;
                    fireProgress(new DownloadProgress(phase, downloaded, contentLength, frac));
                    lastReport = now;
                }
            }
        } finally {
            conn.disconnect();
        }
        RecordableMod.LOGGER.info("[FfmpegBundle] Downloaded {} bytes to {}", downloaded, target);
    }

    private static HttpURLConnection openConnectionFollowingRedirects(String urlStr, int maxHops) throws IOException {
        String current = urlStr;
        for (int i = 0; i < maxHops; i++) {
            URL url;
            try {
                url = URI.create(current).toURL();
            } catch (IllegalArgumentException iae) {
                throw new IOException("Invalid URL: " + current, iae);
            }
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Refusing non-HTTPS URL: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Record-able/" + getModVersion() + " (+https://modrinth.com/mod/record-able)");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return conn;
            }
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) {
                    throw new IOException("HTTP " + code + " redirect with no Location header from " + current);
                }
                if (loc.startsWith("/")) {
                    loc = url.getProtocol() + "://" + url.getHost() + loc;
                }
                current = loc;
                continue;
            }
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + current);
        }
        throw new IOException("Too many redirects starting at " + urlStr);
    }

    private static String getModVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("recordable")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Fetches a hash file (.sha256 or .md5) and parses out the first hex token.
     * Returns null if the file is unreachable.
     */
    private static String tryFetchExpectedHash(String url, String algoLabel) {
        try {
            HttpURLConnection conn = openConnectionFollowingRedirects(url, 5);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) return null;
                // Lines often look like:  "abcdef1234... *filename" or "abcdef1234... filename"
                String token = line.trim().split("\\s+")[0];
                // Validate hex
                if (token.matches("[0-9a-fA-F]+")) {
                    RecordableMod.LOGGER.info("[FfmpegBundle] Fetched expected {}: {}", algoLabel, token);
                    return token;
                }
                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] Could not fetch {} from {}: {}", algoLabel, url, e.getMessage());
            return null;
        }
    }

    private static String computeSha256(Path file) throws IOException {
        return computeHash(file, "SHA-256");
    }

    private static String computeMd5(Path file) throws IOException {
        return computeHash(file, "MD5");
    }

    private static String computeHash(Path file, String algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Permissions and platform helpers
    // ------------------------------------------------------------------

    public static String getExecutableName() {
        return PlatformUtils.isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    private static boolean setExecutablePermission(Path path) {
        try {
            if (path.toFile().setExecutable(true, false)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", "+x", path.toAbsolutePath().toString()});
            return p.waitFor() == 0;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] chmod failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Clears the macOS Gatekeeper {@code com.apple.quarantine} extended attribute
     * from a freshly downloaded binary.
     *
     * <p>Files downloaded from the internet are tagged with this attribute, which
     * makes macOS refuse to execute them (the JVM sees the launch fail with a
     * permission/noexec-style error even though the +x bit is set). Stripping the
     * attribute with {@code xattr -d com.apple.quarantine} allows the bundled
     * FFmpeg binary to run without the user having to right-click "Open" or approve
     * it in System Settings.</p>
     *
     * <p>This is a no-op on non-macOS platforms. Failures are non-fatal and only
     * logged at debug level: the attribute may already be absent (e.g. Gatekeeper
     * disabled), in which case {@code xattr} exits non-zero and that is fine.</p>
     */
    private static void clearMacOSQuarantine(Path path) {
        if (!PlatformUtils.isMacOS()) {
            return;
        }
        try {
            // -d removes the attribute; if it is not present xattr returns non-zero,
            // which is harmless. We deliberately do not treat that as an error.
            Process p = Runtime.getRuntime().exec(new String[]{
                    "xattr", "-d", "com.apple.quarantine", path.toAbsolutePath().toString()
            });
            int code = p.waitFor();
            if (code == 0) {
                RecordableMod.LOGGER.info("[FfmpegBundle] Cleared com.apple.quarantine from {}", path);
            } else {
                RecordableMod.LOGGER.debug("[FfmpegBundle] xattr -d com.apple.quarantine on {} returned {} "
                        + "(attribute likely absent - safe to ignore)", path, code);
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[FfmpegBundle] Failed to clear com.apple.quarantine on {}: {}",
                    path, e.getMessage());
        }
        // Make sure the execute bit is still set after touching xattrs.
        setExecutablePermission(path);
    }

    // ------------------------------------------------------------------
    // ARM detection (still used by PlatformUtils status text)
    // ------------------------------------------------------------------

    static String detectArmArchitecture() {
        return switch (AndroidPlatform.detectArchitecture()) {
            case ARM64 -> "arm64";
            case ARM32 -> "arm32";
            default -> "unknown";
        };
    }

    // ------------------------------------------------------------------
    // UI-friendly descriptions
    // ------------------------------------------------------------------

    public static String getStatusDescription() {
        String path = getBundledFfmpegPath();
        if (path != null) {
            return "FFmpeg ready: " + path;
        }
        return switch (status.get()) {
            case DOWNLOADING -> "Downloading FFmpeg from " + getDownloadSourceDescription();
            case ERROR -> "FFmpeg download failed: " + (lastError == null ? "unknown error" : lastError);
            case AVAILABLE -> "FFmpeg ready";
            case NOT_FOUND -> {
                if (isAutoDownloadSupported()) {
                    yield "FFmpeg not installed - click 'Download FFmpeg' to fetch it ("
                            + getEstimatedDownloadSize() + " from " + getDownloadSourceDescription() + ").";
                }
                yield "FFmpeg not installed. " + getManualInstallInstructions();
            }
        };
    }

    /** Plain-text manual install instructions for the current platform. */
    public static String getManualInstallInstructions() {
        return switch (PlatformUtils.detectPlatform()) {
            case WINDOWS -> "Manual install: download ffmpeg-release-essentials.zip from "
                    + "https://www.gyan.dev/ffmpeg/builds/ and extract ffmpeg.exe to "
                    + getBundleDirectory();
            case LINUX -> "Manual install: install via 'sudo apt install ffmpeg' (Debian/Ubuntu), "
                    + "'sudo dnf install ffmpeg' (Fedora), 'sudo pacman -S ffmpeg' (Arch), "
                    + "or download from https://johnvansickle.com/ffmpeg/ and place 'ffmpeg' at "
                    + getBundleDirectory();
            case MACOS -> "Manual install: 'brew install ffmpeg' or download from "
                    + "https://evermeet.cx/ffmpeg/ and place 'ffmpeg' at " + getBundleDirectory();
            case ANDROID -> getAndroidManualInstructions();
            case UNKNOWN -> "Please install FFmpeg from https://ffmpeg.org/ and add it to PATH.";
        };
    }

    /** Android-specific manual install guidance (fallback when auto-download is unavailable/fails). */
    public static String getAndroidManualInstructions() {
        String autoNote = AndroidPlatform.isArm64()
                ? "On arm64-v8a devices, try the in-game 'Download FFmpeg' button first. If that fails "
                        + "(noexec/SELinux), use one of these manual options:\n"
                : "Auto-download requires an arm64-v8a device. Manual options:\n";
        return autoNote
                + "  1. Install Termux from F-Droid and run: pkg install ffmpeg\n"
                + "     Then set 'ffmpegPath' in the config to /data/data/com.termux/files/usr/bin/ffmpeg.\n"
                + "  2. Download a static arm64 build (e.g. from hzw1199/Android-FFmpeg-Prebuilt) and place\n"
                + "     it at " + getBundleDirectory().resolve("ffmpeg") + " then chmod +x it.";
    }

    /**
     * Runs a diagnostics dump - kept minimal because the lite version has fewer
     * fallback paths than the previous bundled version.
     */
    public static void runDiagnostics() {
        RecordableMod.LOGGER.info("[FfmpegBundle] ── Diagnostics (lite) ──");
        AndroidPlatform.logDiagnostics();
        RecordableMod.LOGGER.info("[FfmpegBundle]   Platform: {}", PlatformUtils.detectPlatform().displayName());
        RecordableMod.LOGGER.info("[FfmpegBundle]   os.arch:  {}", System.getProperty("os.arch", "?"));
        RecordableMod.LOGGER.info("[FfmpegBundle]   Game dir: {}", FabricLoader.getInstance().getGameDir());
        RecordableMod.LOGGER.info("[FfmpegBundle]   Bundle dir: {} (exists={})",
                getBundleDirectory(), Files.exists(getBundleDirectory()));
        RecordableMod.LOGGER.info("[FfmpegBundle]   Auto-download supported: {}", isAutoDownloadSupported());
        RecordableMod.LOGGER.info("[FfmpegBundle]   Download source: {}", getDownloadSourceDescription());
        RecordableMod.LOGGER.info("[FfmpegBundle]   Current status:  {}", getStatusDescription());
        Path candidate = getBundleDirectory().resolve(getExecutableName());
        RecordableMod.LOGGER.info("[FfmpegBundle]   Candidate file: {} (exists={}, executable={})",
                candidate, Files.exists(candidate),
                Files.exists(candidate) && Files.isExecutable(candidate));
    }
}
