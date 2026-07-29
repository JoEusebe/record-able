package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Android-specific environment detection and path resolution for the Record-able mod.
 *
 * <p>Record-able runs as a Fabric mod inside an Android Minecraft launcher's JVM
 * (PojavLauncher, Zalith, FoldCraftLauncher, etc.). Android's Bionic libc, SELinux
 * policies, scoped storage, and W^X (write-XOR-execute) mounts make FFmpeg handling
 * fundamentally different from desktop platforms. This class centralises all of the
 * Android-only logic so the rest of the codebase can stay platform-agnostic.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li><b>Launcher detection</b> - which Android launcher is hosting us
 *       (Pojav / Zalith / FCL) and its package name.</li>
 *   <li><b>Architecture detection</b> - ARM64 ({@code arm64-v8a}) vs ARM32
 *       ({@code armeabi-v7a}) vs x86, and distinguishing Android ARM from a
 *       standard Linux ARM box (e.g. a Raspberry Pi).</li>
 *   <li><b>Path resolution</b> - locating the {@code .minecraft} directory,
 *       choosing an exec-capable directory for the FFmpeg binary (preferring
 *       {@code POJAV_NATIVEDIR} / {@code java.io.tmpdir}), and picking a
 *       file-manager-visible output directory for recordings.</li>
 * </ul>
 *
 * <p>All detection is best-effort and heavily logged for field diagnostics, since
 * Android launcher internals vary widely across devices and versions.</p>
 */
public final class AndroidPlatform {

    private AndroidPlatform() {
    }

    // ------------------------------------------------------------------
    // Launcher detection
    // ------------------------------------------------------------------

    /** Known Android Minecraft launchers that can host Fabric mods. */
    public enum Launcher {
        /** PojavLauncher (net.kdt.pojavlaunch) and its main forks. */
        POJAV("PojavLauncher"),
        /** Zalith Launcher (com.movtery.zalithlauncher, incl. v2 and PojavZH lineage). */
        ZALITH("Zalith Launcher"),
        /** FoldCraftLauncher (com.tungsten.fcl). */
        FCL("FoldCraftLauncher"),
        /** Android launcher detected but the specific product is unknown. */
        UNKNOWN("Unknown Android launcher");

        private final String displayName;

        Launcher(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Package names grouped by launcher. The first matching package wins, so the
     * order is from most-specific to least-specific within each launcher family.
     */
    private static final String[] POJAV_PACKAGES = {
            "net.kdt.pojavlaunch",
            "net.kdt.pojavlaunch.debug",
    };
    private static final String[] ZALITH_PACKAGES = {
            "com.movtery.zalithlauncher.v2",
            "com.movtery.zalithlauncher",
            "com.movtery.pojavzh",
            "com.movtery.zalern",
            "com.movtery.zalith",
    };
    private static final String[] FCL_PACKAGES = {
            "com.tungsten.fcl",
            "com.tungsten.fclauncher",
    };

    private static volatile Launcher cachedLauncher;
    private static volatile String cachedPackageName;
    private static volatile boolean packageProbed;

    /**
     * Detects which Android launcher is hosting the mod. Result is cached.
     * Returns {@link Launcher#UNKNOWN} when on Android but the specific launcher
     * cannot be identified, and is only meaningful when {@link PlatformUtils#isAndroid()}.
     */
    public static Launcher detectLauncher() {
        Launcher cached = cachedLauncher;
        if (cached != null) {
            return cached;
        }
        Launcher detected = probeLauncher();
        cachedLauncher = detected;
        RecordableMod.LOGGER.info("[AndroidPlatform] Launcher: {} (package={})",
                detected.displayName(), getPackageName());
        return detected;
    }

    private static Launcher probeLauncher() {
        String pkg = getPackageName();
        if (pkg != null) {
            Launcher byPkg = classifyPackage(pkg);
            if (byPkg != Launcher.UNKNOWN) {
                return byPkg;
            }
        }
        // Fall back to scanning known launcher data directories on disk.
        for (String p : POJAV_PACKAGES) {
            if (dataDirExists(p)) return Launcher.POJAV;
        }
        for (String p : ZALITH_PACKAGES) {
            if (dataDirExists(p)) return Launcher.ZALITH;
        }
        for (String p : FCL_PACKAGES) {
            if (dataDirExists(p)) return Launcher.FCL;
        }
        return Launcher.UNKNOWN;
    }

    private static Launcher classifyPackage(String pkg) {
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String p : POJAV_PACKAGES) {
            if (lower.equals(p)) return Launcher.POJAV;
        }
        for (String p : ZALITH_PACKAGES) {
            if (lower.equals(p)) return Launcher.ZALITH;
        }
        for (String p : FCL_PACKAGES) {
            if (lower.equals(p)) return Launcher.FCL;
        }
        // Heuristic fallbacks for forks not in the explicit lists.
        if (lower.contains("pojav")) return Launcher.POJAV;
        if (lower.contains("zalith") || lower.contains("movtery")) return Launcher.ZALITH;
        if (lower.contains("tungsten") || lower.contains("fcl")) return Launcher.FCL;
        return Launcher.UNKNOWN;
    }

    private static boolean dataDirExists(String pkg) {
        return Files.exists(Path.of("/data/data/" + pkg))
                || Files.exists(Path.of("/data/user/0/" + pkg))
                || Files.exists(Path.of("/storage/emulated/0/Android/data/" + pkg));
    }

    /** @return {@code true} if the host launcher is PojavLauncher or a close fork. */
    public static boolean isPojavLauncher() {
        return detectLauncher() == Launcher.POJAV || System.getenv("POJAV_NATIVEDIR") != null;
    }

    /** @return {@code true} if the host launcher is Zalith (incl. v2 / PojavZH). */
    public static boolean isZalithLauncher() {
        return detectLauncher() == Launcher.ZALITH;
    }

    /** @return {@code true} if the host launcher is FoldCraftLauncher. */
    public static boolean isFoldCraftLauncher() {
        return detectLauncher() == Launcher.FCL;
    }

    /**
     * Best-effort detection of the host launcher's Android package name
     * (e.g. {@code net.kdt.pojavlaunch}). Returns {@code null} if it cannot
     * be derived from any available path. Result is cached.
     */
    public static String getPackageName() {
        if (packageProbed) {
            return cachedPackageName;
        }
        synchronized (AndroidPlatform.class) {
            if (packageProbed) {
                return cachedPackageName;
            }
            cachedPackageName = probePackageName();
            packageProbed = true;
            return cachedPackageName;
        }
    }

    private static String probePackageName() {
        // Try a series of paths that typically embed the host package name.
        List<String> candidates = new ArrayList<>();
        try {
            candidates.add(FabricLoader.getInstance().getGameDir().toAbsolutePath().toString());
        } catch (Exception ignored) {
        }
        candidates.add(System.getProperty("user.dir", ""));
        candidates.add(System.getProperty("user.home", ""));
        candidates.add(System.getProperty("java.io.tmpdir", ""));
        candidates.add(System.getenv("POJAV_NATIVEDIR"));
        candidates.add(System.getenv("HOME"));
        for (String c : candidates) {
            String pkg = extractPackageName(c);
            if (pkg != null) {
                return pkg;
            }
        }
        return null;
    }

    /**
     * Extracts an Android package name from a filesystem path of the form
     * {@code /data/data/<pkg>/...}, {@code /data/user/0/<pkg>/...}, or
     * {@code /storage/emulated/0/Android/data/<pkg>/...}.
     */
    static String extractPackageName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] prefixes = {
                "/data/data/",
                "/data/user/0/",
                "/data/user/",
                "/storage/emulated/0/Android/data/",
                "/sdcard/Android/data/",
        };
        for (String prefix : prefixes) {
            int idx = path.indexOf(prefix);
            if (idx >= 0) {
                String rest = path.substring(idx + prefix.length());
                // /data/user/<n>/<pkg> - skip the numeric user id segment.
                if (prefix.equals("/data/user/") && !rest.isEmpty() && Character.isDigit(rest.charAt(0))) {
                    int s = rest.indexOf('/');
                    if (s < 0) continue;
                    rest = rest.substring(s + 1);
                }
                int slash = rest.indexOf('/');
                String candidate = slash > 0 ? rest.substring(0, slash) : rest;
                if (looksLikePackage(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean looksLikePackage(String s) {
        return s != null
                && s.contains(".")
                && !s.contains(" ")
                && s.length() > 3
                && s.matches("[A-Za-z0-9_.]+");
    }

    // ------------------------------------------------------------------
    // Architecture detection
    // ------------------------------------------------------------------

    /** CPU/ABI categories relevant to FFmpeg binary selection on Android. */
    public enum Architecture {
        ARM64("arm64-v8a"),
        ARM32("armeabi-v7a"),
        X86_64("x86_64"),
        X86("x86"),
        UNKNOWN("unknown");

        private final String abi;

        Architecture(String abi) {
            this.abi = abi;
        }

        /** Android ABI string, e.g. {@code arm64-v8a}. */
        public String abi() {
            return abi;
        }
    }

    private static volatile Architecture cachedArch;

    /** Detects the CPU architecture / Android ABI. Result is cached. */
    public static Architecture detectArchitecture() {
        Architecture cached = cachedArch;
        if (cached != null) {
            return cached;
        }
        Architecture detected = probeArchitecture();
        cachedArch = detected;
        RecordableMod.LOGGER.info("[AndroidPlatform] Architecture: {} (abi={}, os.arch={})",
                detected.name(), detected.abi(), System.getProperty("os.arch", "?"));
        return detected;
    }

    private static Architecture probeArchitecture() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) return Architecture.ARM64;
        if (osArch.contains("armv8")) return Architecture.ARM64;
        if (osArch.contains("arm")) return Architecture.ARM32;
        if (osArch.contains("x86_64") || osArch.contains("amd64")) return Architecture.X86_64;
        if (osArch.contains("x86") || osArch.contains("i686") || osArch.contains("i386")) return Architecture.X86;

        // Fall back to /proc/cpuinfo when os.arch is unhelpful (some Android JVMs).
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuinfo)) {
                String content = Files.readString(cpuinfo).toLowerCase(Locale.ROOT);
                if (content.contains("aarch64") || content.contains("armv8")) return Architecture.ARM64;
                if (content.contains("armv7") || content.contains("arm")) return Architecture.ARM32;
            }
        } catch (Exception ignored) {
        }
        return Architecture.UNKNOWN;
    }

    /** @return {@code true} on 64-bit ARM ({@code arm64-v8a}). */
    public static boolean isArm64() {
        return detectArchitecture() == Architecture.ARM64;
    }

    /** @return {@code true} on 32-bit ARM ({@code armeabi-v7a}). */
    public static boolean isArm32() {
        return detectArchitecture() == Architecture.ARM32;
    }

    /**
     * Distinguishes <b>Android ARM</b> from a <b>standard Linux ARM</b> box
     * (e.g. a Raspberry Pi running desktop Linux). Both report {@code os.name=Linux}
     * and an ARM {@code os.arch}, but only Android exposes the Bionic/ART runtime
     * and {@code /data/data} style storage. This matters because Android needs
     * Bionic-linked FFmpeg builds while standard Linux ARM uses glibc builds.
     *
     * @return {@code true} only when running on Android with an ARM CPU
     */
    public static boolean isAndroidArm() {
        return PlatformUtils.isAndroid() && (isArm64() || isArm32());
    }

    /**
     * @return {@code true} when running on a standard (non-Android) Linux ARM
     *         system - glibc userland, not Bionic. Useful to avoid mistakenly
     *         downloading an Android FFmpeg build on a Pi/SBC.
     */
    public static boolean isStandardLinuxArm() {
        if (PlatformUtils.isAndroid()) {
            return false;
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("linux") && (isArm64() || isArm32());
    }

    // ------------------------------------------------------------------
    // Path resolution - .minecraft directory
    // ------------------------------------------------------------------

    /**
     * Resolves the active {@code .minecraft} directory. On Android this is the
     * Fabric game directory, which the launcher points at e.g.
     * {@code /storage/emulated/0/Android/data/<pkg>/files/.minecraft/} (Android 10+)
     * or {@code /storage/emulated/0/games/PojavLauncher/.minecraft/} (Android 9).
     *
     * <p>Falls back to scanning well-known launcher locations if the Fabric game
     * dir is somehow unavailable.</p>
     */
    public static Path findMinecraftDirectory() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            if (Files.isDirectory(gameDir)) {
                return gameDir;
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[AndroidPlatform] Fabric game dir unavailable: {}", e.getMessage());
        }
        // Fallback: probe known launcher .minecraft locations.
        for (Path p : knownMinecraftCandidates()) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        // Last resort: return the Fabric game dir even if unverified.
        return FabricLoader.getInstance().getGameDir();
    }

    private static List<Path> knownMinecraftCandidates() {
        List<Path> out = new ArrayList<>();
        String pkg = getPackageName();
        if (pkg != null) {
            out.add(Path.of("/storage/emulated/0/Android/data/" + pkg + "/files/.minecraft"));
            out.add(Path.of("/sdcard/Android/data/" + pkg + "/files/.minecraft"));
            out.add(Path.of("/data/data/" + pkg + "/files/.minecraft"));
            out.add(Path.of("/data/user/0/" + pkg + "/files/.minecraft"));
        }
        // Legacy Android 9 PojavLauncher path.
        out.add(Path.of("/storage/emulated/0/games/PojavLauncher/.minecraft"));
        out.add(Path.of("/sdcard/games/PojavLauncher/.minecraft"));
        return out;
    }

    // ------------------------------------------------------------------
    // Path resolution - FFmpeg binary storage (needs execute permission)
    // ------------------------------------------------------------------

    /** Filename used for the FFmpeg binary copied into an exec-capable directory. */
    public static final String FFMPEG_EXEC_NAME = "recordable-ffmpeg";
    /** Filename used for the ffprobe binary copied into an exec-capable directory. */
    public static final String FFPROBE_EXEC_NAME = "recordable-ffprobe";

    // ------------------------------------------------------------------
    // Termux integration
    // ------------------------------------------------------------------
    //
    // Termux installs its packages under /data/data/com.termux/files/usr, which is
    // an app-private dir that - unlike a Minecraft launcher's data dir - is
    // explicitly mounted exec-capable (Termux owns the package and ships its own
    // exec-allowed prefix). If the user has run `pkg install ffmpeg` in Termux,
    // its ffmpeg/ffprobe binaries are the most reliable way to run FFmpeg on
    // modern Android (API 29+), where the W^X / SELinux policy blocks executing
    // binaries we copy into the launcher's own storage.

    /** Termux prefix bin directory (where `pkg install ffmpeg` puts binaries). */
    public static final String TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin";
    /** Absolute path to a Termux-installed ffmpeg, if present. */
    public static final String TERMUX_FFMPEG_PATH = TERMUX_BIN_DIR + "/ffmpeg";
    /** Absolute path to a Termux-installed ffprobe, if present. */
    public static final String TERMUX_FFPROBE_PATH = TERMUX_BIN_DIR + "/ffprobe";

    /**
     * Returns the path to a Termux-installed {@code ffmpeg} if the binary exists,
     * regardless of whether we can stat it as executable (Termux's exec dir is
     * trusted). Returns {@code null} when Termux/ffmpeg is not present.
     */
    public static Path getTermuxFfmpegPath() {
        Path p = Path.of(TERMUX_FFMPEG_PATH);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * Returns the path to a Termux-installed {@code ffprobe} if present, else {@code null}.
     */
    public static Path getTermuxFfprobePath() {
        Path p = Path.of(TERMUX_FFPROBE_PATH);
        return Files.isRegularFile(p) ? p : null;
    }

    /** @return {@code true} if a Termux-installed ffmpeg binary exists on disk. */
    public static boolean hasTermuxFfmpeg() {
        return getTermuxFfmpegPath() != null;
    }

    /**
     * Returns an ordered list of directories that may permit binary execution on
     * Android, most-preferred first. Per the W^X / SELinux constraints, app data
     * dirs are frequently {@code noexec}; the launcher's native dir and JVM temp
     * dir are the most reliable exec-capable locations.
     *
     * <p>Priority order:</p>
     * <ol>
     *   <li>{@code POJAV_NATIVEDIR} - Pojav/Zalith native lib dir, always exec-allowed</li>
     *   <li>{@code java.io.tmpdir} - the JVM temp/cache dir, usually exec-capable</li>
     *   <li>App internal {@code files/} and {@code cache/} dirs (pre-Android 10)</li>
     * </ol>
     */
    public static List<Path> getExecCapableDirectories() {
        // LinkedHashSet preserves order while de-duplicating.
        Set<Path> dirs = new LinkedHashSet<>();

        // Termux's bin dir is the most reliable exec-capable location on modern
        // Android - list it first so a Termux-installed ffmpeg is found before we
        // ever attempt the (frequently noexec) launcher data dirs.
        if (Files.isDirectory(Path.of(TERMUX_BIN_DIR))) {
            addDir(dirs, Path.of(TERMUX_BIN_DIR));
        }

        String nativeDir = System.getenv("POJAV_NATIVEDIR");
        if (nativeDir != null && !nativeDir.isBlank()) {
            addDir(dirs, Path.of(nativeDir));
        }

        String tmpDir = System.getProperty("java.io.tmpdir", "");
        if (!tmpDir.isBlank()) {
            addDir(dirs, Path.of(tmpDir).resolve("recordable"));
            addDir(dirs, Path.of(tmpDir));
        }

        String pkg = getPackageName();
        if (pkg != null) {
            addDir(dirs, Path.of("/data/data/" + pkg + "/files/recordable"));
            addDir(dirs, Path.of("/data/user/0/" + pkg + "/files/recordable"));
            addDir(dirs, Path.of("/data/data/" + pkg + "/cache/recordable"));
            addDir(dirs, Path.of("/data/user/0/" + pkg + "/cache/recordable"));
        }

        // Cache/native dirs derived from common env vars as a final fallback.
        String tmpEnv = System.getenv("TMPDIR");
        if (tmpEnv != null && !tmpEnv.isBlank()) {
            addDir(dirs, Path.of(tmpEnv).resolve("recordable"));
        }

        return new ArrayList<>(dirs);
    }

    private static void addDir(Set<Path> set, Path p) {
        if (p != null) {
            set.add(p.toAbsolutePath().normalize());
        }
    }

    /**
     * Picks the best directory to store and execute the FFmpeg binary from, creating
     * it if necessary. Each candidate from {@link #getExecCapableDirectories()} is
     * tested for writability and (when possible) execute capability. The first
     * candidate that passes is returned.
     *
     * @return a writable, ideally exec-capable directory, or {@code null} if none
     *         could be prepared
     */
    public static Path resolveFfmpegExecDir() {
        Path firstWritable = null;
        for (Path dir : getExecCapableDirectories()) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                RecordableMod.LOGGER.debug("[AndroidPlatform] Cannot create {}: {}", dir, e.getMessage());
                continue;
            }
            if (!Files.isWritable(dir)) {
                continue;
            }
            if (firstWritable == null) {
                firstWritable = dir;
            }
            if (isDirExecCapable(dir)) {
                RecordableMod.LOGGER.info("[AndroidPlatform] Selected exec-capable FFmpeg dir: {}", dir);
                return dir;
            }
        }
        if (firstWritable != null) {
            RecordableMod.LOGGER.warn("[AndroidPlatform] No verified exec-capable dir; "
                    + "falling back to writable dir (exec may be blocked): {}", firstWritable);
        } else {
            RecordableMod.LOGGER.warn("[AndroidPlatform] No writable directory found for FFmpeg binary");
        }
        return firstWritable;
    }

    /**
     * Tests whether a directory permits executing a file placed inside it. Writes a
     * tiny shell stub, marks it executable, and attempts to run it. This is the most
     * reliable way to detect {@code noexec} mounts and SELinux denials at runtime.
     */
    private static boolean isDirExecCapable(Path dir) {
        Path probe = dir.resolve(".recordable-exec-probe");
        try {
            Files.write(probe, "#!/system/bin/sh\nexit 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!probe.toFile().setExecutable(true, false) && !Files.isExecutable(probe)) {
                return false;
            }
            Process proc = new ProcessBuilder(probe.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            boolean exited = proc.waitFor(3, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("[AndroidPlatform] Exec probe failed in {}: {}", dir, e.getMessage());
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Path resolution - recordings output (user/file-manager accessible)
    // ------------------------------------------------------------------

    /**
     * Resolves a recordings output directory that is reachable through the launcher's
     * built-in file manager and through Android file managers (ZArchiver, MiXplorer).
     * On Android the only reliably writable location for a mod is inside the active
     * {@code .minecraft} directory, so recordings land in
     * {@code <.minecraft>/recordable/recordings/}.
     *
     * @return an absolute, file-manager-visible recordings directory
     */
    public static Path resolveRecordingsOutputDir() {
        Path mcDir = findMinecraftDirectory();
        Path recordings = mcDir.resolve("recordable").resolve("recordings");
        try {
            Files.createDirectories(recordings);
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("[AndroidPlatform] Could not create recordings dir {}: {}",
                    recordings, e.getMessage());
        }
        return recordings.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /** Dumps the resolved Android environment to the log for field diagnostics. */
    public static void logDiagnostics() {
        if (!PlatformUtils.isAndroid()) {
            return;
        }
        RecordableMod.LOGGER.info("[AndroidPlatform] ── Android environment ──");
        RecordableMod.LOGGER.info("[AndroidPlatform]   Launcher:     {}", detectLauncher().displayName());
        RecordableMod.LOGGER.info("[AndroidPlatform]   Package:      {}", getPackageName());
        RecordableMod.LOGGER.info("[AndroidPlatform]   Architecture: {} ({})",
                detectArchitecture().name(), detectArchitecture().abi());
        RecordableMod.LOGGER.info("[AndroidPlatform]   AndroidArm:   {}", isAndroidArm());
        RecordableMod.LOGGER.info("[AndroidPlatform]   .minecraft:   {}", findMinecraftDirectory());
        RecordableMod.LOGGER.info("[AndroidPlatform]   POJAV_NATIVEDIR: {}", System.getenv("POJAV_NATIVEDIR"));
        RecordableMod.LOGGER.info("[AndroidPlatform]   java.io.tmpdir:  {}", System.getProperty("java.io.tmpdir", "?"));
        RecordableMod.LOGGER.info("[AndroidPlatform]   Termux ffmpeg:   {}", getTermuxFfmpegPath());
        RecordableMod.LOGGER.info("[AndroidPlatform]   Exec candidates: {}", getExecCapableDirectories());
        RecordableMod.LOGGER.info("[AndroidPlatform]   Recordings dir:  {}", resolveRecordingsOutputDir());
    }
}
