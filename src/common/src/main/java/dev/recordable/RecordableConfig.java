package dev.recordable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.recordable.filter.FilterType;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemePreset;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Persistent JSON configuration for Record-able.
 *
 * <p>The file is stored at {@code config/recordable.json}. Public fields keep
 * the JSON concise and user-editable, while helper methods sanitize values and
 * translate friendly options into encoder/capture settings.</p>
 */
public final class RecordableConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "recordable.json";
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");

    // mp4/mkv/mov are H.264 containers (fast, real-time-safe). webm is VP9 (smaller files,
    // heavier to encode). The recorder routes the codec automatically from this container.
    public static final String[] FORMATS = {"mp4", "mkv", "mov", "webm"};
    public static final int[] FPS_VALUES = {24, 30, 60, 120};
    /** Selectable frame-rates for the (lightweight) auto-clip / kill-montage capture. */
    public static final int[] AUTO_CLIP_FPS_VALUES = {15, 20, 24, 30, 45, 60};
    public static final String[] RESOLUTIONS = {"native", "1080p", "720p", "480p"};
    public static final String[] QUALITIES = {"high", "balanced", "performance"};
    /** Capture FPS options for deferred render mode (potato-PC friendly rates). */
    public static final int[] DEFERRED_CAPTURE_FPS = {5, 10, 15, 20, 30};
    /** Output FPS options for deferred render mode (smooth target rates). */
    public static final int[] DEFERRED_TARGET_FPS = {30, 60, 120};
    /** Interpolation methods for deferred render: none, duplicate (CFR), motion (minterpolate/RIFE). */
    public static final String[] DEFERRED_INTERPOLATION = {"none", "duplicate", "motion"};
    /**
     * Android records through a GLES translation layer (LTW/Pojav/Zalith) where
     * glReadPixels readback is far slower than desktop. Most phone GPUs render
     * Minecraft at 18-25 FPS; recording at 30 FPS produces ~40% duplicate frames
     * which manifest as visible freeze-and-jump stutter. Capping at 24 FPS keeps
     * duplicate rates under ~25% on typical hardware while staying close to film
     * cadence. Users can override this per-session by lowering their FPS setting.
     * The user's saved fps preference is never modified; this cap is runtime-only.
     */
    public static final int ANDROID_MAX_FPS = 24;
    /** Divisor applied to capture dimensions on Android (1 = native resolution). */
    public static final int ANDROID_CAPTURE_SCALE_DIVISOR = 1;
    /**
     * Replay-buffer quality presets (separate from the recording {@link #QUALITIES}).
     * <ul>
     *   <li><b>source</b> - Same as Recording (no extra downscale, recording fps)</li>
     *   <li><b>balanced</b> - 720p30</li>
     *   <li><b>performance</b> - 480p30</li>
     *   <li><b>high</b> - 1080p60</li>
     * </ul>
     */
    public static final String[] REPLAY_QUALITIES = {"source", "balanced", "performance", "high"};
    public static final String[] AUTO_RECORD_TRIGGERS = {"world_join", "game_start", "manual"};
    public static final String[] AUTO_STOP_TRIGGERS = {"world_leave", "game_quit", "never"};
    public static final String[] AUDIO_CHANNELS = {"auto", "mono", "stereo"};
    public static final int[] AUDIO_SAMPLE_RATES = {44100, 48000};

    public enum VideoEncoder {
        SOFTWARE("Software (x264)", "libx264"),
        NVIDIA("NVIDIA NVENC", "h264_nvenc"),
        AMD("AMD AMF", "h264_amf"),
        INTEL("Intel QuickSync", "h264_qsv");

        public final String displayName;
        public final String ffmpegCodec;

        VideoEncoder(String displayName, String ffmpegCodec) {
            this.displayName = displayName;
            this.ffmpegCodec = ffmpegCodec;
        }
    }

    /**
     * Presets for audio sync delay compensation.
     * With deferred audio start and dynamic gap measurement, the default (Auto)
     * is now 0ms because FFmpeg already handles AAC priming internally.
     * Manual presets remain for edge cases (unusual audio drivers, hardware quirks).
     */
    public enum AudioDelayPreset {
        AUTO("Auto", -1),
        NONE("None", 0),
        DESKTOP("Desktop", 46),
        ANDROID("Android", 60),
        CUSTOM("Custom", -2);

        public final String displayName;
        /** Sentinel: -1 = auto-detect by platform, -2 = use custom value from config. */
        public final int defaultMs;

        AudioDelayPreset(String displayName, int defaultMs) {
            this.displayName = displayName;
            this.defaultMs = defaultMs;
        }

        /** Cycle to the next preset in order. */
        public AudioDelayPreset next() {
            AudioDelayPreset[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        /** Cycle to the previous preset in order. */
        public AudioDelayPreset previous() {
            AudioDelayPreset[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    /**
     * Preset positions for the recording overlay on the HUD.
     * Each position anchors the overlay panel to a corner or center of the screen,
     * with safe margins to avoid vanilla HUD elements.
     */
    public enum OverlayPosition {
        TOP_LEFT("Top-Left"),
        TOP_RIGHT("Top-Right"),
        BOTTOM_LEFT("Bottom-Left"),
        BOTTOM_RIGHT("Bottom-Right"),
        CENTER_TOP("Center-Top");

        public final String displayName;

        OverlayPosition(String displayName) {
            this.displayName = displayName;
        }

        /** Cycle to the next position preset. */
        public OverlayPosition next() {
            OverlayPosition[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        /** Cycle to the previous position preset. */
        public OverlayPosition previous() {
            OverlayPosition[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    /** Visual style presets for the optional on-screen mouse pointer. */
    public enum MousePointerTheme {
        CLASSIC("OS-Style"),
        EDGY_DARK("Edgy dark"),
        BOOTLEG_TOY_PHONE("Bootleg toy phone");

        public final String displayName;

        MousePointerTheme(String displayName) {
            this.displayName = displayName;
        }

        public MousePointerTheme next() {
            MousePointerTheme[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        public MousePointerTheme previous() {
            MousePointerTheme[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    /**
     * On-screen overlay style presets rendered via Minecraft's HUD system.
     * Visible to the player in real-time while recording (OBS-style preview).
     */
    public enum OverlayStyleHud {
        // NOTE: the enum constant CLASSIC is intentionally kept (rather than renamed to
        // SPEEDRUNNER) so existing config files that stored "CLASSIC" keep working.
        CLASSIC("Speed-Runner's Classic"),
        // The three former VHS variants (VHS_CLASSIC / VHS_MINIMAL / VHS_FULL) are unified
        // into a single "VHS" style. What used to differ between those presets is now driven
        // entirely by the per-element VHS detail toggles (vhsShowBrackets, vhsShowPlay,
        // vhsShowSp, vhsShowDate, vhsShowBattery, vhsShowAudioMeter, vhsShowTapeCounter).
        VHS("VHS"),
        SYNTHWAVE("Synthwave"),
        NONE("None");

        public final String displayName;

        OverlayStyleHud(String displayName) {
            this.displayName = displayName;
        }

        public OverlayStyleHud next() {
            OverlayStyleHud[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        public OverlayStyleHud previous() {
            OverlayStyleHud[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    public enum AudioEncoder {
        // NOTE: the WebM muxer only accepts Opus or Vorbis audio. AAC/MP3/FLAC are NOT valid
        // in WebM (FFmpeg aborts the mux), so "webm" is intentionally absent from those rows.
        AAC("AAC", "aac", new String[]{"mp4", "mkv", "mov", "avi"}, 192),
        OPUS("Opus", "libopus", new String[]{"webm", "mkv"}, 128),
        MP3("MP3", "libmp3lame", new String[]{"mp4", "mkv", "mov", "avi"}, 192),
        FLAC("FLAC (Lossless)", "flac", new String[]{"mkv", "mov", "avi", "mp4"}, 0);

        public final String displayName;
        public final String ffmpegCodec;
        public final String[] supportedContainers;
        public final int defaultBitrateKbps;

        AudioEncoder(String displayName, String ffmpegCodec, String[] supportedContainers, int defaultBitrateKbps) {
            this.displayName = displayName;
            this.ffmpegCodec = ffmpegCodec;
            this.supportedContainers = supportedContainers;
            this.defaultBitrateKbps = defaultBitrateKbps;
        }

        public boolean supportsContainer(String container) {
            if (container == null || container.isBlank()) {
                return false;
            }
            String normalized = container.trim().toLowerCase(Locale.ROOT);
            for (String supported : supportedContainers) {
                if (supported.equalsIgnoreCase(normalized)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isLossless() {
            return defaultBitrateKbps <= 0;
        }
    }

    private static volatile RecordableConfig instance;
    private static Path configPath;

    public String format = "mp4";
    public int fps = 60;
    public String resolution = "1080p";
    public String quality = "balanced";
    /** Use "auto" for calculated bitrate, or values such as "8M" / "4500k". */
    public String bitrate = "auto";
    public VideoEncoder encoder = VideoEncoder.SOFTWARE;

    public boolean captureAudio = true;
    public String audioSource = "game";
    /** Audio device identifier: "auto" for auto-detect, or an explicit device name (e.g. "Stereo Mix", "WASAPI:Speakers"). */
    public String audioDevice = "auto";
    public AudioEncoder audioEncoder = AudioEncoder.AAC;
    /** Audio bitrate in kbps (used for lossy encoders). */
    public int audioBitrateKbps = 192;
    /** Audio sample rate in Hz. */
    public int audioSampleRate = 48000;
    /** Audio channels: 1=mono, 2=stereo. */
    public int audioChannelCount = 2;
    /** Audio volume percentage. 100 = normal, 0 = mute, 200 = 2x boost. */
    public int audioVolume = 100;
    /** Audio volume boost in dB applied during muxing (0-24 dB, default 0). Stacks with audioVolume. */
    public int audioVolumeBoostDb = 0;

    // ---- Dual audio input (game audio + microphone) ----
    /** Whether to also capture the microphone and mix it into the recording. Default: false. */
    public boolean captureMicrophone = true;
    /** Microphone device identifier: "auto" for the system default input, or an explicit device name. */
    public String microphoneDevice = "auto";
    /** Game audio mix level percentage (0-200). Used as the amix weight for the game-audio input. 100 = normal. */
    public int gameAudioVolume = 100;
    /** Microphone mix level percentage (0-200). Used as the amix weight for the microphone input. 100 = normal. */
    public int microphoneVolume = 80;
    /**
     * Push-to-Talk mode for the microphone. When {@code true}, the microphone is only
     * mixed into the recording while the Push-to-Talk hotkey is held down. When
     * {@code false} (default), the microphone is always live for the whole recording
     * (when {@link #captureMicrophone} is enabled).
     */
    public boolean microphonePushToTalk = false;
    /**
     * Whether to capture Simple Voice Chat audio (the voices of other players) directly
     * through the Simple Voice Chat plugin API and overlay it into the recording.
     *
     * <p>Only has an effect when the optional Simple Voice Chat mod is installed. When the
     * in-game audio is being captured through the OpenAL loopback device, incoming voice
     * chat is already recorded as part of the game audio, so this direct overlay is
     * automatically skipped to avoid doubled/echoed voices. It is used as a fallback so
     * voice chat still ends up in the recording when the loopback path is unavailable
     * (for example when another recording mod owns the OpenAL device). Default: true.</p>
     */
    public boolean includeVoiceChat = true;
    /**
     * Whether to apply FFT-based noise suppression (OBS-style) to the microphone during capture.
     * Uses FFmpeg's {@code afftdn} adaptive denoiser to attenuate steady background noise
     * (fans, hum, hiss) while preserving speech. Default: false.
     */
    public boolean noiseSuppression = false;

    public String audioBitrate = "192k";
    public String audioChannels = "auto";

    /** Legacy field kept for config migration. Always treated as true. */
    public boolean useFFmpegIfAvailable = true;

    /**
     * Whether to use a bundled FFmpeg binary if one is available
     * (from a companion bundle mod or embedded resource).
     * When true, the mod checks .minecraft/recordable/ffmpeg/bin/ before
     * falling back to system PATH. Default: true.
     */
    public boolean useBundledFfmpeg = true;
    /**
     * Auto-detected path to the bundled FFmpeg binary.
     * This is populated at runtime and saved for informational purposes.
     * Users should not normally edit this. Set to empty string if not detected.
     */
    public String bundledFfmpegPath = "";

    /**
     * Optional user-supplied path to an FFmpeg executable. When set (non-blank)
     * this is tried <em>first</em>, before the bundled binary and the system
     * {@code PATH}. This is the recommended workaround on Android, where users
     * typically install FFmpeg through Termux and point this at:
     * {@code /data/data/com.termux/files/usr/bin/ffmpeg}. Empty = auto-detect.
     */
    public String ffmpegPath = "";

    public boolean enabled = true;
    public String outputDir = "recordings";
    public boolean showOverlay = true;
    /** Draw a visible mouse pointer overlay while recording. Useful for tutorials. */
    public boolean showMousePointer = false;
    /** Mouse pointer size scale as a percentage (50-300). 100 = default size. */
    public int mousePointerScale = 100;
    /** Visual style of the optional mouse pointer overlay. */
    public MousePointerTheme mousePointerTheme = MousePointerTheme.CLASSIC;

    /**
     * Default naming pattern used for new manual recordings. The user edits this
     * from the "Rename File Name" field at the top of the settings screen. It may
     * contain the tokens {@code {datetime}}, {@code {date}} and {@code {time}}, which
     * are expanded when a recording starts.
     */
    public static final String DEFAULT_FILENAME_PATTERN = "recordable-{datetime}";

    /**
     * Filename pattern (without extension) for new manual recordings. Tokens:
     * {@code {datetime}} -> yyyyMMdd-HHmmss, {@code {date}} -> yyyyMMdd,
     * {@code {time}} -> HHmmss. Literal text is kept as-is. Illegal filename
     * characters are stripped when the pattern is resolved.
     */
    public String filenamePattern = DEFAULT_FILENAME_PATTERN;

    /**
     * When {@code false}, the FFmpeg welcome screen will show on the next game start
     * if FFmpeg is not detected. Set to {@code true} after the user has seen the screen
     * or explicitly dismissed it.
     */
    public boolean ffmpegFirstRunShown = false;
    /**
     * V1-0.08 Streamer Mode: whether to bake the censor overlay into the recording.
     * Lives in the Streamer Mode screen.
     *
     * <p>Controls whether censor regions are composited into the saved video
     * (see {@code StreamerModeManager.applyCensoring}):</p>
     *
     * <ul>
     *   <li>{@code false} (the default): your recording is clean (no censor in the
     *       saved video). Instead the censor shows as a live on-screen overlay (like
     *       a watermark) that obstructs scoreboards, coordinates, GUI elements and
     *       open inventories. Regions are layered (they stack), and the overlay can
     *       be hidden/shown with the "Toggle Censor Overlay" hotkey (see
     *       {@link #hotkeyToggleCensorOverlay} / {@link #censorOverlayHidden}).</li>
     *   <li>{@code true}: the censor is baked into the recording. It may also appear
     *       on your live screen (controlled by {@link #streamerShowCensorPreview}).</li>
     * </ul>
     *
     * <p>This does not affect the game HUD or the recording info overlay - those
     * are always captured exactly as seen on screen.</p>
     */
    public boolean bakeInOverlay = false;
    public boolean showHomeButton = true;
    /** Title-screen home button custom X/Y, -1 means use the default anchor. */
    public int homeButtonX = -1;
    public int homeButtonY = -1;
    /**
     * Hidden easter-egg flag. Not exposed in any settings UI: it must be toggled by
     * hand-editing the config JSON (set "iskasaisora": true). When enabled, the title
     * screen flashes white once and draws red arrows around the Record-able home button,
     * and pressing that button opens a small thank-you screen instead of the video
     * collection. Defaults to false so normal users never see it.
     */
    public boolean iskasaisora = false;
    /**
     * Hidden counter tracking how many times the easter-egg thank-you screen has
     * been opened. Once it reaches 2, the red guide arrows around the home button
     * stop appearing (the egg itself still works). Persisted like any other config
     * value; not exposed in any settings UI.
     */
    public int iskasaisoraSeen = 0;
    /**
     * Permanent easter-egg kill switch. When true, the easter egg is fully
     * disabled and will NOT trigger for anyone, including the hardcoded fallback
     * accounts, regardless of the {@link #iskasaisora} flag. This is set by the
     * "Say bye to Lilly" button on the thank-you screen and can only be undone by
     * hand-editing the config JSON back to {@code "iskasaisoraOff": false}.
     */
    public boolean iskasaisoraOff = false;
    public boolean stopOnDisconnect = true;
    public boolean showPerformanceStats = false;
    /** Copy recordings to Android gallery folder (/storage/emulated/0/Movies/Record-able) for easy access. */
    public boolean saveToGalleryOnAndroid = true;
    /**
     * When enabled on Android, each finished recording is compressed to a mobile-friendly
     * 720p copy (kept alongside the original) in a background thread. Off by default.
     */
    public boolean autoCompressOnAndroid = false;
    /** 0 means unlimited. */
    public int maxFileSizeMB = 0;

    public String overlayColor = "#FF0000";
    public String menuAccentColor = "#FF0000";

    /**
     * Overlay position on the HUD. Default: TOP_LEFT (classic position).
     */
    public OverlayPosition overlayPosition = OverlayPosition.TOP_LEFT;

    /**
     * Overlay scale as a percentage (50-200). 100 = default size.
     * Affects text rendering and panel dimensions.
     */
    public int overlayScale = 100;

    /**
     * Audio delay preset selection. Controls how the audio sync offset is determined.
     * AUTO = detect based on platform, NONE = 0ms, DESKTOP = 46ms, ANDROID = 60ms,
     * CUSTOM = use audioSyncOffsetMs value directly.
     */
    public AudioDelayPreset audioDelayPreset = AudioDelayPreset.AUTO;

    /**
     * Audio sync offset in milliseconds applied during muxing via FFmpeg's -itsoffset.
     * Compensates for AAC encoder priming delay + capture pipeline latency.
     * Only used when audioDelayPreset is CUSTOM. Default: 46.
     *
     * Range: -500 to 500 ms.
     *   Positive values delay the audio (use when audio plays too early / ahead of video).
     *   Negative values advance the audio (use when audio plays too late / behind video).
     * Example: audio that is 120 ms late is corrected with audioSyncOffsetMs = -120.
     */
    public int audioSyncOffsetMs = 46;

    /**
     * Returns the effective audio delay in milliseconds based on the current preset.
     * AUTO now returns 0ms since deferred audio start + dynamic gap measurement
     * handle synchronization, and FFmpeg handles AAC priming internally.
     * Manual presets (Desktop/Android/Custom) remain for edge-case fine-tuning.
     */
    public int getEffectiveAudioDelay() {
        if (audioDelayPreset == null) {
            audioDelayPreset = AudioDelayPreset.AUTO;
        }
        return switch (audioDelayPreset) {
            case AUTO -> 0;
            case NONE -> 0;
            case DESKTOP -> 46;
            case ANDROID -> 60;
            case CUSTOM -> Math.max(-500, Math.min(500, audioSyncOffsetMs));
        };
    }

    /**
     * Unified automatic recording toggle. When enabled, recording starts and
     * stops automatically based on {@link #autoRecordTrigger} / {@link #autoStopTrigger}
     * (e.g. World Join / World Leave). This single control replaces the former
     * separate "Auto-Record on Join" quick toggle - world-join recording is now
     * driven entirely by this flag combined with the "world_join" start trigger.
     */
    public boolean autoRecord = true;
    public String autoRecordTrigger = "world_join";
    public String autoStopTrigger = "world_leave";
    /** Seconds. */
    public int autoRecordDelay = 2; // Auto-configured, not user-adjustable

    // === Feature 2: Configurable Hotkeys ===
    //
    // Compile-time defaults used for first-time auto-assignment detection.
    // When the config value equals the default, the auto-assignment system
    // may reassign the key to avoid conflicts with other mods or vanilla.
    // Once a user changes a hotkey, the config value diverges from the
    // default and will never be auto-reassigned.

    /** Default for toggle recording: minus key (-) */
    public static final int DEFAULT_HOTKEY_TOGGLE_RECORDING = GLFW.GLFW_KEY_MINUS;
    /** Default for pause/resume: equals key (=) */
    public static final int DEFAULT_HOTKEY_PAUSE_RESUME = GLFW.GLFW_KEY_EQUAL;
    /** Compile-time default for open settings. */
    public static final int DEFAULT_HOTKEY_OPEN_SETTINGS = GLFW.GLFW_KEY_F9;
    /** Compile-time default for open video collection. */
    public static final int DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION = GLFW.GLFW_KEY_F12;
    /** Compile-time default for Push-to-Talk: the V key (common voice-chat default). */
    public static final int DEFAULT_HOTKEY_PUSH_TO_TALK = GLFW.GLFW_KEY_V;

    /** GLFW key code for toggle recording. */
    public int hotkeyToggleRecording = DEFAULT_HOTKEY_TOGGLE_RECORDING;
    /** GLFW key code for pause/resume. Default: equals key (=) */
    public int hotkeyPauseResume = DEFAULT_HOTKEY_PAUSE_RESUME;
    /** GLFW key code for open settings. */
    public int hotkeyOpenSettings = DEFAULT_HOTKEY_OPEN_SETTINGS;
    /** GLFW key code for open video collection. */
    public int hotkeyOpenVideoCollection = DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION;
    /** GLFW key code for Push-to-Talk (hold to enable mic). Default: V. */
    public int hotkeyPushToTalk = DEFAULT_HOTKEY_PUSH_TO_TALK;
    /**
     * GLFW key code for toggling the live Streamer Mode censor overlay on/off.
     * Default: unbound (rebindable in Options &gt; Controls). Only relevant when
     * "Bake in Overlay" is OFF, where the censor acts as a live on-screen overlay.
     */
    public int hotkeyToggleCensorOverlay = GLFW.GLFW_KEY_UNKNOWN;
    /**
     * GLFW key code for opening the full-screen live Censor Editor overlay, where
     * the player can add, move, stretch and remove censor bars over the running
     * game. Default: unbound (rebindable in Options &gt; Controls).
     */
    public int hotkeyOpenCensorEditor = GLFW.GLFW_KEY_UNKNOWN;
    /**
     * GLFW key code for cancelling (discarding) the current recording. Cancelling
     * stops capture and deletes the in-progress output file instead of saving it.
     * Default: unbound (rebindable in Options &gt; Controls).
     */
    public int hotkeyCancelRecording = GLFW.GLFW_KEY_UNKNOWN;

    /**
     * GLFW key code for naming (renaming) the recording just saved. After a
     * recording finishes a toast invites the player to press this key within a
     * short window to open the rename screen; otherwise the default name is kept.
     * Default: N (rebindable in Options &gt; Controls).
     */
    public int hotkeyNameRecording = GLFW.GLFW_KEY_N;

    // === Feature 3: Recording Profiles ===
    // Recording Templates (simplified from profiles)
    public static final String[] TEMPLATES = {"custom", "cinematic", "balanced", "pvp_clip"};
    public String activeTemplate = "custom";

    // === Feature 5: Disk Space Guardian ===
    /** Warn when disk usage exceeds this percentage (0-100). */
    public int diskSpaceWarnPercent = 90;
    /** Block recording when disk usage exceeds this percentage (0-100). */
    public int diskSpaceBlockPercent = 95;
    /**
     * Disable the disk-usage recording block, normally triggered when used disk space reaches
     * {@link #diskSpaceBlockPercent}. This keeps the warning path active but allows recording
     * to continue at the user's own risk.
     */
    public boolean disableDiskSpaceUsageBlock = false;
    /** Minimum free space in MB before warning. */
    public int diskSpaceMinFreeMB = 500;

    // === Feature 7: Replay Buffer ===
    /** Whether the replay buffer is enabled. */
    public boolean replayBufferEnabled = false;
    /** Duration of the replay buffer in seconds. */
    public int replayBufferDurationSeconds = 30;

    // === Deferred Render Mode (Offline Smooth-Render) ===
    /** Enable deferred render mode (capture at low FPS, render smooth video offline afterward). */
    public boolean deferredRenderEnabled = false;
    /** Capture frame rate for deferred mode (5/10/15/20/30 fps). Lower = less FPS hit during gameplay. */
    public int deferredCaptureFps = 10;
    /** Target output frame rate for offline rendering (30/60/120 fps). */
    public int deferredTargetFps = 60;
    /** Interpolation method: "none" (duplicate frames), "duplicate" (smart CFR), "motion" (RIFE/minterpolate). */
    public String deferredInterpolation = "duplicate";
    /** Automatically start rendering when recording stops (if false, adds to Pending Renders queue). */
    public boolean deferredAutoRender = false;
    /**
     * Show the "Recording captured! Render now or save for later?" prompt after each deferred
     * recording. When false (the default) the prompt is skipped: the session either auto-renders
     * (when {@link #deferredAutoRender} is true) or is saved silently to the Pending Renders queue,
     * so the user is not interrupted after every recording.
     */
    public boolean deferredShowRenderPrompt = false;
    /** Keep temp frames after rendering (allows re-render with different settings). */
    public boolean deferredKeepTempFrames = false;

    // === Feature 8: Recording Timer Display ===
    /** Show the recording timer on the overlay. */
    public boolean showRecordingTimer = true;
    /** Show estimated file size on the overlay. */
    public boolean showEstimatedFileSize = true;

    // === Feature 6: Toast Notification ===
    /** Show a toast notification when recording finishes. */
    public boolean showPostRecordingToast = true;

    /** Prompt for a recording name (rename screen) right after a recording is saved. */
    public boolean promptRenameAfterRecording = true;

    // === On-Screen VHS Overlay (rendered via Minecraft HUD, visible while recording) ===

    /** Which overlay style to render on-screen while recording. */
    public OverlayStyleHud overlayStyleHud = OverlayStyleHud.CLASSIC;

    /**
     * When enabled, the on-screen overlay is "skinned" with the colors of the currently
     * selected {@link #uiTheme} (the menu UI theme). This applies the theme's accent,
     * panel and text colors on top of whatever overlay style/layout is active, so the
     * overlay matches the rest of the mod's look. When disabled, the overlay uses its
     * own default / per-element colors. The skin always follows whatever UI Theme is
     * selected (there is no separate skin list to manage).
     */
    public boolean overlaySkinEnabled = false;

    /** Resolve the color palette that the overlay skin should use (follows the UI Theme). */
    public ThemeColors overlaySkin() {
        return ThemeColors.forPreset(uiTheme != null ? uiTheme : ThemePreset.CLASSIC);
    }

    /** Returns the active overlay skin palette, or {@code null} when skinning is disabled. */
    public ThemeColors activeOverlaySkinOrNull() {
        return overlaySkinEnabled ? overlaySkin() : null;
    }

    // ── Per-element color customization (hex strings) ──
    /** Color for "PLAY" text. */
    public String vhsPlayColor = "#FFFFFF";
    /** Color for "REC" text. */
    public String vhsRecTextColor = "#FFFFFF";
    /** Color for the recording dot (●). */
    public String vhsRecDotColor = "#CC1E1E";
    /** Color for corner bracket lines. */
    public String vhsBracketColor = "#C8FFFFFF";
    /** Color for the elapsed-time / timestamp text. */
    public String vhsTimestampColor = "#FFFFFF";
    /** Color for the date/time stamp text. */
    public String vhsDateColor = "#FFFFFF";
    /** Color for the "SP" indicator text. */
    public String vhsSpColor = "#FFFFFF";

    // ── VHS detail toggles ──
    /** Show corner bracket lines around the screen. */
    public boolean vhsShowBrackets = true;
    /** Show "PLAY ▶" label. */
    public boolean vhsShowPlay = true;
    /** Show date/time stamp. */
    public boolean vhsShowDate = true;
    /** Show "SP" speed indicator. */
    public boolean vhsShowSp = true;
    /** Show battery indicator. */
    public boolean vhsShowBattery = true;
    /** Show audio level meters. */
    public boolean vhsShowAudioMeter = true;
    /** Show tape counter. */
    public boolean vhsShowTapeCounter = true;

    // ── Overlay element positions (pixel coordinates) ──
    // PLAY/REC cluster (top-left anchor: X from left edge, Y from top edge)
    /** PLAY/REC text X position from left edge. */
    public int hudPlayRecX = 80;
    /** PLAY/REC text Y position from top edge. */
    public int hudPlayRecY = 14;

    // Timestamp (top-right anchor: X = offset from right edge, Y from top)
    /** Timestamp offset from right edge. */
    public int hudTimestampOffsetX = 14;
    /** Timestamp Y position from top edge. */
    public int hudTimestampY = 14;

    // SP indicator (bottom-left: X from left edge, Y = offset from bottom edge)
    /** SP indicator X position from left edge. */
    public int hudSpX = 80;
    /** SP indicator offset from bottom edge. */
    public int hudSpOffsetY = 24;

    // Performance panel (bottom-right: offsets from edges)
    /** Performance panel offset from right edge. */
    public int hudPerfOffsetX = 8;
    /** Performance panel offset from bottom edge. */
    public int hudPerfOffsetY = 80;

    // VHS details cluster (bottom-right: date, TC, battery, audio meter)
    /** VHS details offset from right edge. */
    public int hudDetailsOffsetX = 14;
    /** VHS details offset from bottom edge. */
    public int hudDetailsOffsetY = 14;

    // Corner lines/brackets (small viewfinder rectangle near PLAY area)
    /** Corner lines rectangle X (from left edge). */
    public int hudCornersX = 68;
    /** Corner lines rectangle Y (from top edge). */
    public int hudCornersY = 4;
    /** Corner lines rectangle width. */
    public int hudCornersWidth = 100;
    /** Corner lines rectangle height. */
    public int hudCornersHeight = 48;

    // ── Element sizes (width/height overrides, 0 = auto-size from content) ──
    /** PLAY/REC cluster width override. 0 = auto. */
    public int hudPlayRecW = 0;
    /** PLAY/REC cluster height override. 0 = auto. */
    public int hudPlayRecH = 0;
    /** Timestamp width override. 0 = auto. */
    public int hudTimestampW = 0;
    /** Timestamp height override. 0 = auto. */
    public int hudTimestampH = 0;
    /** SP indicator width override. 0 = auto. */
    public int hudSpW = 0;
    /** SP indicator height override. 0 = auto. */
    public int hudSpH = 0;
    /** Performance panel width override. 0 = auto. */
    public int hudPerfW = 0;
    /** Performance panel height override. 0 = auto. */
    public int hudPerfH = 0;
    /** VHS details cluster width override. 0 = auto. */
    public int hudDetailsW = 0;
    /** VHS details cluster height override. 0 = auto. */
    public int hudDetailsH = 0;

    // ── Per-element opacity (0-100, default 100 = fully opaque) ──
    public int hudPlayRecOpacity = 100;
    public int hudTimestampOpacity = 100;
    public int hudCornersOpacity = 100;
    public int hudSpOpacity = 100;
    public int hudDetailsOpacity = 100;
    public int hudPerfOpacity = 100;

    // ── Layer order (render order, first = bottom, last = top) ──
    /** Comma-separated element IDs defining render order (bottom to top). */
    public String hudLayerOrder = defaultLayerOrder();

    // Per-element visibility toggles (editor eye icon)
    public boolean hudPlayRecVisible = true;
    public boolean hudTimestampVisible = true;
    public boolean hudCornersVisible = true;
    public boolean hudSpVisible = true;
    public boolean hudDetailsVisible = true;
    public boolean hudPerfVisible = true;

    // ── Live-preview video filters (on-screen approximation, toggled in Position & Colors editor) ──
    /** Master toggle (global kill-switch): draw the live filter preview overlays on screen.
     *  Individual filters are still controlled by their per-filter eye toggles below. */
    public boolean showFiltersLive = true;
    /** Per-filter visibility toggles (editor eye icon). */
    public boolean filterVhsVisible = false;
    public boolean filterLcdMoireVisible = false;
    public boolean filterCrtVisible = false;
    /** Per-filter intensity (0-100). */
    public int filterVhsIntensity = 75;
    public int filterLcdMoireIntensity = 75;
    public int filterCrtIntensity = 75;

    // ── Mic / Push-to-Talk indicator (movable independently of overlay style) ──
    /** Mic indicator top-left X in scaled GUI coords. -1 = auto (top-center). */
    public int hudMicX = -1;
    /** Mic indicator top Y in scaled GUI coords. */
    public int hudMicY = 4;
    /** Mic indicator visibility (editor eye toggle). */
    public boolean hudMicVisible = true;
    /** Mic indicator opacity (0-100). */
    public int hudMicOpacity = 100;

    // ── Classic / Synthwave single-panel position (movable via the element editor) ──
    // These styles draw one fixed panel rather than individual HUD elements. The editor
    // now exposes that panel as a single draggable element so it can be repositioned.
    /** Classic info-panel top-left X in scaled GUI coords. -1 = auto (derive from overlayPosition). */
    public int hudClassicX = -1;
    /** Classic info-panel top-left Y in scaled GUI coords. -1 = auto (derive from overlayPosition). */
    public int hudClassicY = -1;
    /** Classic panel visibility (editor eye toggle). */
    public boolean hudClassicVisible = true;
    /** Synthwave panel top-left X in scaled GUI coords. -1 = auto (top-left inset). */
    public int hudSynthX = -1;
    /** Synthwave panel top-left Y in scaled GUI coords. -1 = auto (top-left inset). */
    public int hudSynthY = -1;
    /** Synthwave panel visibility (editor eye toggle). */
    public boolean hudSynthVisible = true;

    // === Feature: Recording Bookmarks ===
    /** Enable the bookmark hotkey during recording (unbound by default). */
    public boolean bookmarksEnabled = true;

    // === Feature: Smart Auto-Clip Triggers ===
    /** Master toggle for the entire auto-clip feature. When false, no auto-clip
     *  triggers fire regardless of the individual trigger settings below. */
    public boolean autoClipEnabled = false;
    /** Auto-start a short clip when the player earns an achievement/advancement. */
    public boolean autoClipOnAchievement = false;
    /** Auto-start a short clip when the player dies. */
    public boolean autoClipOnDeath = false;
    /** Build a pre-death "where it all went wrong" clip category from recent mistake signals. */
    public boolean autoClipHindsightEnabled = true;
    /** How many seconds of pre-death context to keep for hindsight mode clips. */
    public int autoClipHindsightLookbackSeconds = 20;
    /** Auto-start a short clip when the player changes dimension (Nether, End, etc.). */
    public boolean autoClipOnDimensionChange = false;
    /** Auto-start a short clip when a boss mob is killed (Ender Dragon, Wither). */
    public boolean autoClipOnBossKill = false;
    /** Auto-start a short clip when the player kills any mob/entity. */
    public boolean autoClipOnKill = false;
    /** Auto-start a short clip when the player kills another player (PvP). */
    public boolean autoClipOnPlayerKill = false;
    /** Auto-start a short clip when the player's Totem of Undying pops (survives lethal damage). */
    public boolean autoClipOnTotemPop = false;
    /**
     * (Experimental) Auto-start a short clip when a user-defined custom event occurs.
     * The list of event types is configured in {@link #customEventTriggers}.
     */
    public boolean autoClipOnCustomEvent = false;
    /**
     * List of custom event trigger identifiers. Each entry is a lowercase event key:
     * "low_health", "xp_level_up", "weather_change", "full_inventory".
     * Only checked when {@link #autoClipOnCustomEvent} is true.
     */
    public List<String> customEventTriggers = new ArrayList<>();
    /** All available custom event trigger keys. */
    public static final String[] CUSTOM_EVENT_TRIGGERS = {
        "low_health", "xp_level_up", "weather_change", "full_inventory"
    };
    /** Human-readable display names for custom event triggers (parallel to CUSTOM_EVENT_TRIGGERS). */
    public static final String[] CUSTOM_EVENT_TRIGGER_NAMES = {
        "Low Health (<20%)", "XP Level Up", "Weather Change", "Full Inventory"
    };
    /** Duration of auto-clip recordings in seconds. */
    public int autoClipDuration = 30;

    /**
     * Kill-montage mode. When true, kill triggers (On Kill / On Player Kill) produce a
     * short "montage" clip that starts a moment BEFORE the finishing blow, includes the
     * kill, and ends a moment AFTER, instead of a forward-only auto-clip recording.
     * This requires a brief rolling pre-roll buffer that is only active during combat.
     */
    public boolean autoClipKillMontage = true;
    /** Seconds of footage to keep BEFORE the finishing blow in a kill montage. */
    public int autoClipKillPreSeconds = 1;
    /** Seconds of footage to keep AFTER the kill in a kill montage. */
    public int autoClipKillPostSeconds = 1;
    /**
     * Capture frame-rate used for auto-clip / kill-montage clips, independent of the main
     * recording FPS. Kept deliberately separate so the lightweight montage capture can run at
     * a lower (or higher) rate than the full recording. Clamped to {@link #AUTO_CLIP_FPS_VALUES}.
     */
    public int autoClipFps = 30;
    /**
     * When true, auto-clip / kill-montage clips include the game audio captured around the clip
     * window (muxed via the rolling OpenAL loopback buffer). When false, clips are video-only.
     */
    public boolean autoClipAudio = true;

    // ============================================================
    // === Chat Notification Toggles (per-category, default ON) ===
    // ============================================================
    /** Show chat messages about the main recording lifecycle (start/stop/save/errors). */
    public boolean notifyRecording = true;
    /** Show chat messages about auto-clips and kill montages. */
    public boolean notifyClips = true;
    /** Show chat messages about the replay buffer. */
    public boolean notifyReplayBuffer = true;
    /** Show chat messages about automatic recording. */
    public boolean notifyAutoRecord = true;
    /** Show chat messages about bookmarks/chapter markers. */
    public boolean notifyBookmarks = true;
    /** Show warning chat messages (disk space, mod conflicts, etc.). */
    public boolean notifyWarnings = true;

    // ============================================================
    // === Replay/Flashback Compatibility Bridge ===
    // ============================================================
    /**
     * Master toggle for the replay-mod compatibility bridge. When enabled and a replay mod
     * (Flashback, Replay Mod) is present, Record-able yields the OpenAL loopback audio device
     * to that mod (falling back to system audio for its own recordings) and replaces the hard
     * "conflict" warning with a softer "managed coexistence" note.
     */
    public boolean replayCompatBridge = true;
    /**
     * When enabled (and the bridge is on), Record-able automatically starts a screen recording
     * while a replay/flashback timeline is being played back or rendered, and stops it when
     * playback ends. Only stops recordings the bridge itself started.
     */
    public boolean replayAutoRecordPlayback = false;
    /**
     * When enabled (and the bridge is on), Record-able gives up ("yields") the OpenAL loopback
     * audio device to the replay mod and captures its own audio from the system loopback
     * (Stereo Mix / monitor) instead. Default AUTO: when Flashback is detected, automatically
     * yield to prevent silent recording conflicts. Users can override explicitly in settings.
     *
     * Values: true=always yield, false=never yield, null=auto (yield if Flashback detected).
     */
    public Boolean replayYieldAudioDevice = null;  // null = auto mode

    // ============================================================
    // === V1-0.06 FEATURE 1: Replay Buffer (extended) ===
    // ============================================================
    /** Encoding quality preset used when saving a replay buffer clip. */
    public String replayBufferQuality = "balanced";
    /** Hotkey (GLFW key code) to save the current replay buffer to disk. */
    public int hotkeySaveReplayBuffer = GLFW.GLFW_KEY_UNKNOWN;
    /** Show a toast/notification when a replay buffer clip is saved. */
    public boolean replayBufferNotify = true;

    // ============================================================
    // === V1-0.06 FEATURE 2: Recording Gallery ===
    // ============================================================
    public static final String[] GALLERY_SORT_MODES = {"newest", "oldest", "name_az", "name_za", "largest", "smallest", "longest", "shortest"};
    /** Default sort mode for the recording gallery. */
    public String gallerySortMode = "newest";
    /** Show file metadata (size/duration/date) overlays on gallery thumbnails. */
    public boolean galleryShowMetadata = true;
    /** Number of gallery columns (thumbnail grid width). */
    public int galleryColumns = 3;

    // ============================================================
    // === V1-0.06 FEATURE 3: Session Markers & Chapters ===
    // ============================================================
    /** Master toggle for session markers/chapters feature. */
    public boolean markersEnabled = true;
    /** Hotkey (GLFW key code) to add a bookmark/marker during recording. */
    public int hotkeyAddBookmark = GLFW.GLFW_KEY_UNKNOWN;
    /** Export a sidecar .txt file with marker timestamps next to the recording. */
    public boolean exportChapterFile = true;
    /** Embed chapter markers into the video container (MKV/MP4) when supported. */
    public boolean embedChaptersInVideo = false;
    /** Automatically add a chapter marker when recording starts. */
    public boolean autoMarkerOnStart = true;

    // ============================================================
    // === V1-0.06 FEATURE 4: Watermark / Branding ===
    // ============================================================
    /** Master toggle: bake watermarks into the recorded video. */
    public boolean watermarksEnabled = false;
    /** Show watermarks live in the on-screen preview overlay. */
    public boolean showWatermarksLive = true;
    /** Up to 4 configurable watermark slots (image or text). */
    public List<WatermarkSlot> watermarkSlots = new ArrayList<>();
    /** Maximum number of watermark slots allowed. */
    public static final int MAX_WATERMARK_SLOTS = 4;

    // ============================================================
    // === V1-0.06 FEATURE 5: Separate Audio Tracks ===
    // ============================================================
    /** Write multiple discrete audio tracks into the MKV instead of one merged track. */
    public boolean separateAudioTracks = false;
    /** Include the game/system audio track. */
    public boolean trackGameAudio = true;
    /** Include the microphone audio track. */
    public boolean trackMicAudio = true;
    /** Include a separate music/media audio track (if a source is available). */
    public boolean trackMusicAudio = false;

    // ============================================================
    // === V1-0.06 FEATURE 6: Storage Manager ===
    // ============================================================
    /** Enable automatic cleanup of old recordings. MUST default OFF for safety. */
    public boolean autoCleanupEnabled = false;
    /** Auto-delete recordings older than this many days (when auto-cleanup on). */
    public int autoCleanupOlderThanDays = 30;
    /** Auto-cleanup once total recordings size exceeds this many MB (0 = disabled). */
    public int autoCleanupMaxTotalMB = 0;
    /** Filenames the user has flagged as protected (never auto-deleted). */
    public List<String> storageProtectedFiles = new ArrayList<>();
    /** Default CRF used when compressing/recompressing a recording to save space. */
    public int storageCompressionCrf = 28;

    // ============================================================
    // === V1-0.06 FEATURE 7: Performance Optimizer ===
    // ============================================================
    /** Master toggle for the performance optimizer. */
    public boolean perfOptimizerEnabled = false;
    /** Automatically apply optimizations when performance drops below target. */
    public boolean perfAutoAdjust = false;
    /** Target minimum FPS the optimizer tries to maintain. */
    public int perfMinFps = 45;
    /** Prioritize game framerate over recording quality when under load. */
    public boolean perfModeGamePriority = true;
    /** Optimizer action: lower recording resolution under load. */
    public boolean perfActionLowerRes = true;
    /** Optimizer action: lower recording FPS under load. */
    public boolean perfActionLowerFps = true;
    /** Optimizer action: switch to a faster encoder preset under load. */
    public boolean perfActionFasterPreset = true;
    /** Warn the user before automatically applying an optimization. */
    public boolean perfWarnBeforeAdjust = false;
    /** Show the live performance stats overlay while recording. */
    public boolean perfShowStatsOverlay = false;
    /**
     * The device performance preset most recently selected in the Performance
     * screen. Persisted so the Preset button always reflects the user's current
     * selection across sessions instead of resetting to a default.
     */
    public String selectedDevicePreset = "mid_end_pc";

    // ============================================================
    // === V1-0.08 FEATURE: Performance - frame buffer pooling ===
    // ============================================================
    /**
     * Reuse capture/censor frame buffers instead of allocating a fresh array per
     * frame. Greatly reduces GC pressure (and the stutter it causes) during long
     * high-resolution recordings. Safe to leave on.
     */
    public boolean frameBufferPoolingEnabled = true;

    // ============================================================
    // === V1-0.08 FEATURE: Smooth Motion (Lunar rewind style) ===
    // ============================================================
    /**
     * Apply motion smoothing to the recorded video so playback looks fluid even when
     * the game renders below the recording frame rate (the "Lunar rewind" feel).
     * Implemented as an FFmpeg minterpolate filter at encode time.
     */
    public boolean smoothMotionEnabled = false;
    /**
     * Smoothing mode: {@code "blend"} (light frame cross-fade) or {@code "motion"}
     * (motion-compensated interpolation - smoothest but heavier on CPU).
     */
    public String smoothMotionMode = "blend";

    // ============================================================
    // === V1-0.09 FEATURE: Hide Elements (UI toggles) ===
    // ============================================================
    /** Hide chat messages while recording for clean footage. */
    public boolean hideChat = false;
    /** Hide crosshair while recording for clean footage. */
    public boolean hideCrosshair = false;
    /** Hide hotbar while recording for clean footage. */
    public boolean hideHotbar = false;
    /** Hide boss bar while recording for clean footage. */
    public boolean hideBossBar = false;
    /** Hide player hand/item while recording for clean footage. */
    public boolean hideHand = false;
    /** Hide scoreboard while recording for clean footage. */
    public boolean hideScoreboard = false;
    /** Hide vignette overlay while recording for clean footage. */
    public boolean hideVignette = false;

    // ============================================================
    // === V1-0.09 FEATURE: Export Settings ===
    // ============================================================
    /** Export format override (empty = use main format setting). */
    public String exportFormat = "";
    /** Export video codec override (empty = use main encoder). */
    public String exportVideoCodec = "";
    /** Export video bitrate in Mbps (0 = auto). */
    public int exportVideoBitrateMbps = 0;
    /** Export audio codec override (empty = use main audio encoder). */
    public String exportAudioCodec = "";
    /** Export audio bitrate in kbps (0 = use main setting). */
    public int exportAudioBitrateKbps = 0;
    /** Export resolution override (empty = use recording resolution). */
    public String exportResolution = "";
    /** Export FPS override (0 = use recording FPS). */
    public int exportFps = 0;

    // ============================================================
    // === V1-0.08 FEATURE: Streamer Mode (censor overlays) ===
    // ============================================================
    /** Master toggle for streamer mode - applies censor regions to recordings. */
    public boolean streamerModeEnabled = false;
    /** Draw a visible outline of each censor region on the live overlay while recording. */
    public boolean streamerShowCensorPreview = false;
    /**
     * Runtime visibility of the live on-screen censor overlay (the OFF-mode
     * obstruction overlay), toggled by the "Toggle Censor Overlay" hotkey.
     * When {@code true} the live overlay is hidden until toggled back on. This
     * never affects whether the censor is baked into the recording (that is
     * controlled solely by {@link #bakeInOverlay}).
     */
    public boolean censorOverlayHidden = false;
    /** Default censor style applied to newly created regions ("SOLID", "GRADIENT"). */
    public String streamerDefaultCensorStyle = "SOLID";
    /** User-defined censor regions (fractions of the frame). */
    public List<CensorRegion> censorRegions = new ArrayList<>();

    // === UI Theme System ===
    /** Active UI theme preset. */
    public ThemePreset uiTheme = ThemePreset.VHS;
    /** Show scanline effect on UI panels. */
    public boolean uiScanlines = true;
    /** Show film grain noise on UI panels. */
    public boolean uiFilmGrain = true;
    /** Show random VHS glitch bars on UI panels. */
    public boolean uiGlitchEffects = true;
    /** Show vignette (dark corners) on UI panels. */
    public boolean uiVignette = true;
    /** Enable smooth animations (hover, transitions). */
    public boolean uiAnimations = true;
    /** Galaxy theme star glow intensity (0=low, 1=medium, 2=high). */
    public int uiGalaxyGlowIntensity = 1;
    /** Custom accent color override for UI theme (empty = use theme default). */
    public String uiCustomAccentColor = "";

    private RecordableConfig() {
    }

    /**
     * Returns the current config instance.
     * Uses double-checked locking to avoid synchronized overhead on every call.
     */
    public static RecordableConfig get() {
        RecordableConfig inst = instance;
        if (inst == null) {
            synchronized (RecordableConfig.class) {
                inst = instance;
                if (inst == null) {
                    load();
                    inst = instance;
                }
            }
        }
        return inst;
    }

    public static synchronized RecordableConfig load() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                RecordableConfig loaded = GSON.fromJson(reader, RecordableConfig.class);
                instance = loaded == null ? new RecordableConfig() : loaded;
                instance.migrateOldConfig();
                instance.sanitize();
                instance.save();
                return instance;
            } catch (Exception exception) {
                RecordableMod.LOGGER.warn("Failed to load Record-able config at {}. Recreating defaults.", configPath, exception);
            }
        }

        instance = new RecordableConfig();
        instance.sanitize();
        instance.save();
        return instance;
    }

    public synchronized void save() {
        sanitize();

        try {
            Files.createDirectories(getConfigPath().getParent());
            try (Writer writer = Files.newBufferedWriter(
                    getConfigPath(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn("Failed to save Record-able config at {}.", getConfigPath(), exception);
        }
    }

    public static Path getConfigPath() {
        if (configPath == null) {
            configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        }
        return configPath;
    }

    public String getFormat() {
        if (format == null || format.isBlank()) return "mp4";
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        for (String valid : FORMATS) {
            if (valid.equals(normalized)) return normalized;
        }
        return "mp4";
    }

    public int getFps() {
        // Cap the effective recording frame-rate on Android. The GLES readback
        // pipeline cannot sustain 60/120 FPS, so target a realistic 30 FPS while
        // leaving the user's saved fps preference unchanged.
        if (PlatformUtils.isAndroid()) {
            return Math.min(fps, ANDROID_MAX_FPS);
        }
        return fps;
    }

    public String getResolution() {
        return resolution;
    }

    public String getQuality() {
        return quality;
    }

    public boolean isAutoBitrate() {
        return bitrate == null || bitrate.isBlank() || "auto".equalsIgnoreCase(bitrate.trim());
    }

    public String resolveBitrate(int width, int height) {
        if (!isAutoBitrate()) {
            String userBitrate = bitrate.trim();
            long bitrateInKbps = parseBitrateToKbps(userBitrate);
            if (bitrateInKbps > 0 && bitrateInKbps < 500) {
                RecordableMod.LOGGER.warn("WARNING: Configured bitrate '{}' ({} kbps) is very low for {}x{} video. " +
                        "This will produce poor quality. Recommended: at least 2M for 720p, 5M for 1080p. " +
                        "Set to 'auto' for optimal quality.", userBitrate, bitrateInKbps, width, height);
            }
            return userBitrate;
        }

        // On Android, boost quality factors to compensate for typically lower frame rates
        // (capped at 24 FPS) and GLES readback constraints. Higher factors produce better
        // perceived quality in the final recording.
        double qualityFactor;
        if (PlatformUtils.isAndroid()) {
            qualityFactor = switch (quality) {
                case "high" -> 0.15D;
                case "performance" -> 0.070D;
                default -> 0.110D;  // balanced
            };
        } else {
            qualityFactor = switch (quality) {
                case "high" -> 0.120D;
                case "performance" -> 0.050D;
                default -> 0.080D;
            };
        }

        double megabits = width * (double) height * Math.max(1, fps) * qualityFactor / 1_000_000.0D;
        int rounded = (int) Math.round(Math.max(2.0D, Math.min(80.0D, megabits)));
        return rounded + "M";
    }

    /**
     * Parses a bitrate string (e.g., "12k", "5M", "8000k") to approximate kbps.
     * Returns -1 if unparseable.
     */
    private static long parseBitrateToKbps(String bitrateStr) {
        if (bitrateStr == null || bitrateStr.isBlank()) return -1;
        String lower = bitrateStr.trim().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith("m")) {
                return (long) (Double.parseDouble(lower.substring(0, lower.length() - 1)) * 1000);
            } else if (lower.endsWith("k")) {
                return (long) Double.parseDouble(lower.substring(0, lower.length() - 1));
            } else {
                return Long.parseLong(lower) / 1000;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int getX264Crf() {
        return switch (quality) {
            case "high" -> 18;
            case "performance" -> 28;
            default -> 23;
        };
    }

    public String getX264Preset() {
        return switch (quality) {
            case "high" -> "slow";
            case "performance" -> "ultrafast";
            default -> "medium";
        };
    }

    public int getVp9Crf() {
        return switch (quality) {
            case "high" -> 30;
            case "performance" -> 40;
            default -> 35;
        };
    }

    public int getMjpegQuality() {
        return switch (quality) {
            case "high" -> 2;
            case "performance" -> 8;
            default -> 5;
        };
    }

    public Path getOutputDirectory() {
        try {
            boolean isDefault = outputDir == null || outputDir.isBlank() || outputDir.trim().equals("recordings");
            // On Android, route the default location into a file-manager-visible
            // folder inside .minecraft so recordings can be found via the launcher's
            // file browser and Android file managers (ZArchiver, MiXplorer).
            if (isDefault && PlatformUtils.isAndroid()) {
                return AndroidPlatform.resolveRecordingsOutputDir();
            }
            Path configured = Paths.get(isDefault ? "recordings" : outputDir.trim());
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return FabricLoader.getInstance().getGameDir().resolve(configured).normalize();
        } catch (InvalidPathException exception) {
            return FabricLoader.getInstance().getGameDir().resolve("recordings").normalize();
        }
    }

    /**
     * Maps an auto-clip trigger prefix (e.g. "on-kill", "on-totem-pop") to a clean
     * event-type subfolder name. Auto-clips are organized under
     * {@code <outputDir>/clips/<subfolder>/} so each event type stays separate:
     * {@code on totem pop}, {@code kills}, {@code deaths}, {@code custom_events}.
     * Manual and replay recordings are not auto-clips and stay loose in the output root.
     */
    public static String clipSubfolderForPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "custom_events";
        }
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("totem")) {
            return "on totem pop";
        } else if (lower.contains("hindsight")) {
            return "hindsight mode";
        } else if (lower.contains("death")) {
            return "deaths";
        } else if (lower.contains("kill") || lower.contains("boss")) {
            return "kills";
        } else {
            // on-achievement, on-dimension, on-event, and any future/unknown trigger
            return "custom_events";
        }
    }

    /**
     * Default filename pattern per auto-clip trigger type.
     * This keeps saved clip names distinct even when users do not customize naming.
     */
    public static String defaultClipFilenamePatternForPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "auto-clip-{datetime}";
        }
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("hindsight")) {
            return "hindsight-clip-{datetime}";
        } else if (lower.contains("totem")) {
            return "totem-pop-clip-{datetime}";
        } else if (lower.contains("player") && lower.contains("kill")) {
            return "player-kill-clip-{datetime}";
        } else if (lower.contains("boss")) {
            return "boss-kill-clip-{datetime}";
        } else if (lower.contains("kill")) {
            return "kill-clip-{datetime}";
        } else if (lower.contains("death")) {
            return "death-clip-{datetime}";
        } else if (lower.contains("achievement")) {
            return "achievement-clip-{datetime}";
        } else if (lower.contains("dimension")) {
            return "dimension-clip-{datetime}";
        } else if (lower.contains("event") || lower.contains("health")
                || lower.contains("weather") || lower.contains("inventory")
                || lower.contains("level")) {
            return "custom-event-clip-{datetime}";
        }
        return "auto-clip-{datetime}";
    }

    /** Default filename pattern for replay buffer saves. */
    public static String defaultReplayFilenamePattern() {
        return "replay-clip-{datetime}";
    }

    public int getOverlayColorRgb() {
        return parseHexColor(overlayColor, 0xFF0000);
    }

    public int getMenuAccentColorRgb() {
        return parseHexColor(menuAccentColor, 0xFF0000);
    }

    /** Check if a HUD element is visible by its layer ID. */
    public boolean isElementVisible(String elementId) {
        return switch (elementId) {
            case "PLAY/REC" -> hudPlayRecVisible;
            case "Timestamp" -> hudTimestampVisible;
            case "Corners" -> hudCornersVisible;
            case "SP" -> hudSpVisible;
            case "Details" -> hudDetailsVisible;
            case "Perf" -> hudPerfVisible;
            case "Mic" -> hudMicVisible;
            case "Classic" -> hudClassicVisible;
            case "Synthwave" -> hudSynthVisible;
            case "Filter:VHS" -> filterVhsVisible;
            case "Filter:LCD_MOIRE" -> filterLcdMoireVisible;
            case "Filter:CRT" -> filterCrtVisible;
            default -> true;
        };
    }

    /** Set visibility for a HUD element by its layer ID. */
    public void setElementVisible(String elementId, boolean visible) {
        switch (elementId) {
            case "PLAY/REC" -> hudPlayRecVisible = visible;
            case "Timestamp" -> hudTimestampVisible = visible;
            case "Corners" -> hudCornersVisible = visible;
            case "SP" -> hudSpVisible = visible;
            case "Details" -> hudDetailsVisible = visible;
            case "Perf" -> hudPerfVisible = visible;
            case "Mic" -> hudMicVisible = visible;
            case "Classic" -> hudClassicVisible = visible;
            case "Synthwave" -> hudSynthVisible = visible;
            case "Filter:VHS" -> filterVhsVisible = visible;
            case "Filter:LCD_MOIRE" -> filterLcdMoireVisible = visible;
            case "Filter:CRT" -> filterCrtVisible = visible;
        }
    }

    // ── Live-preview filter layer helpers ──
    /** Live-preview filter layers (rendered beneath HUD, first = bottom). */
    public static final String[] FILTER_LAYERS = {"Filter:VHS", "Filter:LCD_MOIRE", "Filter:CRT"};

    /** True if the given layer id refers to a live-preview filter (not a HUD element). */
    public static boolean isFilterLayer(String layerId) {
        return layerId != null && layerId.startsWith("Filter:");
    }

    /** Resolve the {@link FilterType} for a filter layer id, or NONE if not a filter layer. */
    public static FilterType filterLayerToType(String layerId) {
        if (!isFilterLayer(layerId)) return FilterType.NONE;
        return FilterType.fromName(layerId.substring("Filter:".length()));
    }

    /** Get the intensity (0-100) for a filter layer id. */
    public int getFilterIntensity(String layerId) {
        return switch (layerId) {
            case "Filter:VHS" -> filterVhsIntensity;
            case "Filter:LCD_MOIRE" -> filterLcdMoireIntensity;
            case "Filter:CRT" -> filterCrtIntensity;
            default -> 0;
        };
    }

    /** Set the intensity (0-100) for a filter layer id. */
    public void setFilterIntensity(String layerId, int value) {
        int v = Math.max(0, Math.min(100, value));
        switch (layerId) {
            case "Filter:VHS" -> filterVhsIntensity = v;
            case "Filter:LCD_MOIRE" -> filterLcdMoireIntensity = v;
            case "Filter:CRT" -> filterCrtIntensity = v;
        }
    }

    /** Non-filter HUD layers in default render order. */
    private static final String[] HUD_LAYERS = {"Corners", "PLAY/REC", "Timestamp", "SP", "Details", "Perf", "Mic"};

    /** Single-panel overlay styles that expose one movable panel element in the editor. */
    private static final String[] PANEL_LAYERS = {"Classic", "Synthwave"};

    /** All recognized layer ids in default order (filters first so they render at the bottom). */
    public static java.util.List<String> allLayerIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Collections.addAll(ids, FILTER_LAYERS);
        java.util.Collections.addAll(ids, HUD_LAYERS);
        java.util.Collections.addAll(ids, PANEL_LAYERS);
        return ids;
    }

    /** The default render-order string. */
    public static String defaultLayerOrder() {
        return String.join(",", allLayerIds());
    }

    /** HUD element layer ids that only the VHS overlay lays out individually. */
    private static final String[] VHS_HUD_LAYERS = {"Corners", "PLAY/REC", "Timestamp", "SP", "Details", "Perf"};

    /**
     * Returns the layer ids the on-screen element editor should expose for a given
     * overlay style. The list is dynamic so the editor only shows elements the
     * selected style actually renders:
     *
     * <ul>
     *   <li>The live-preview filters (VHS / LCD Moire / CRT) and the Mic indicator
     *       render independently of the chosen HUD style, so they are always shown.</li>
     *   <li>Only the VHS overlay positions individual HUD elements (Corners, PLAY/REC,
     *       Timestamp, SP, Details, Perf), so those appear only for {@code VHS}.</li>
     *   <li>Classic and Synthwave draw a single fixed panel and None draws no HUD,
     *       so for those styles the editor shows just the filters and the Mic.</li>
     * </ul>
     *
     * <p>Ordering matches {@link #allLayerIds()} (filters first so they render at the
     * bottom, Mic last).</p>
     */
    public static java.util.List<String> layerIdsForStyle(OverlayStyleHud style) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Collections.addAll(ids, FILTER_LAYERS);
        if (style == OverlayStyleHud.VHS) {
            java.util.Collections.addAll(ids, VHS_HUD_LAYERS);
        } else if (style == OverlayStyleHud.CLASSIC) {
            ids.add("Classic");
        } else if (style == OverlayStyleHud.SYNTHWAVE) {
            ids.add("Synthwave");
        }
        ids.add("Mic");
        return ids;
    }

    /**
     * Compute the Classic info-panel top-left corner. Honors the absolute
     * {@code hudClassicX}/{@code hudClassicY} when both are set ({@code >= 0});
     * otherwise derives the position from {@link #overlayPosition} using the same
     * formula as the live overlay so the editor preview matches in-game placement.
     */
    public int[] classicPanelPos(int areaW, int areaH, int panelW, int panelH, int margin) {
        if (hudClassicX >= 0 && hudClassicY >= 0) {
            int x = Math.max(0, Math.min(hudClassicX, Math.max(0, areaW - panelW)));
            int y = Math.max(0, Math.min(hudClassicY, Math.max(0, areaH - panelH)));
            return new int[]{x, y};
        }
        OverlayPosition position = overlayPosition != null ? overlayPosition : OverlayPosition.TOP_LEFT;
        int x, y;
        switch (position) {
            case TOP_RIGHT -> { x = areaW - panelW - margin - 130; y = margin; }
            case BOTTOM_LEFT -> { x = margin; y = areaH - panelH - margin - 50; }
            case BOTTOM_RIGHT -> { x = areaW - panelW - margin - 130; y = areaH - panelH - margin - 50; }
            case CENTER_TOP -> { x = (areaW - panelW) / 2; y = margin + 70; }
            default -> { x = margin; y = margin; }
        }
        x = Math.max(margin, Math.min(x, areaW - panelW - margin));
        y = Math.max(margin, Math.min(y, areaH - panelH - margin));
        return new int[]{x, y};
    }

    /**
     * Compute the Synthwave panel top-left corner. Honors the absolute
     * {@code hudSynthX}/{@code hudSynthY} when set ({@code >= 0}); otherwise uses
     * the supplied default insets (top-left) like the live overlay.
     */
    public int[] synthPanelPos(int areaW, int areaH, int panelW, int panelH, int defX, int defY) {
        int x = hudSynthX >= 0 ? hudSynthX : defX;
        int y = hudSynthY >= 0 ? hudSynthY : defY;
        x = Math.max(0, Math.min(x, Math.max(0, areaW - panelW)));
        y = Math.max(0, Math.min(y, Math.max(0, areaH - panelH)));
        return new int[]{x, y};
    }

    public static int applyOpacity(int argb, int opacityPercent) {
        if (opacityPercent >= 100) return argb;
        if (opacityPercent <= 0) return argb & 0x00FFFFFF; // fully transparent
        int alpha = (argb >>> 24) & 0xFF;
        alpha = (alpha * opacityPercent) / 100;
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Parse a hex color string that may include alpha (#AARRGGBB or #RRGGBB)
     * into a full ARGB int suitable for Minecraft's DrawContext methods.
     */
    public static int parseArgbColor(String hexColor, int fallbackArgb) {
        if (hexColor == null || hexColor.isBlank()) return fallbackArgb;
        String h = hexColor.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 8) {
                // Format: AARRGGBB
                return (int) Long.parseLong(h, 16);
            } else if (h.length() == 6) {
                // Format: RRGGBB → 0xFFRRGGBB
                return 0xFF000000 | Integer.parseInt(h, 16);
            }
        } catch (NumberFormatException ignored) {}
        return fallbackArgb;
    }

    public CaptureDimensions resolveCaptureDimensions(int nativeWidth, int nativeHeight) {
        int safeNativeWidth = Math.max(2, nativeWidth);
        int safeNativeHeight = Math.max(2, nativeHeight);
        int maxHeight = switch (resolution) {
            case "1080p" -> 1080;
            case "720p" -> 720;
            case "480p" -> 480;
            default -> safeNativeHeight;
        };

        int targetWidth;
        int targetHeight;
        if ("native".equals(resolution) || safeNativeHeight <= maxHeight) {
            targetWidth = safeNativeWidth;
            targetHeight = safeNativeHeight;
        } else {
            double scale = maxHeight / (double) safeNativeHeight;
            targetWidth = (int) Math.round(safeNativeWidth * scale);
            targetHeight = (int) Math.round(safeNativeHeight * scale);
        }

        // On Android the GLES translation layer makes full-resolution readback +
        // encoding too slow (only a few real fps at 1200x540 in testing), so
        // halve the capture resolution. Desktop dimensions are unchanged.
        if (PlatformUtils.isAndroid()) {
            targetWidth /= ANDROID_CAPTURE_SCALE_DIVISOR;
            targetHeight /= ANDROID_CAPTURE_SCALE_DIVISOR;
        }

        targetWidth = makeEven(targetWidth);
        targetHeight = makeEven(targetHeight);
        return new CaptureDimensions(Math.max(2, targetWidth), Math.max(2, targetHeight));
    }

    public String getPerformanceHint() {
        boolean heavyResolution = "native".equals(resolution) || "1080p".equals(resolution);
        if (PlatformUtils.isAndroid()) {
            if (fps > 24) {
                return "Android: most phones render Minecraft at 18-25 FPS. Recording at "
                        + fps + " FPS duplicates frames and causes stutter. Use 24 FPS for smoother playback.";
            }
            return "Android: 24 FPS matches typical phone GPU throughput and minimises duplicate frames.";
        }
        if (fps >= 120) {
            return "120 FPS recording is expensive. Prefer 60 FPS unless you need slow-motion footage.";
        }
        if (heavyResolution && fps >= 60 && "high".equals(quality)) {
            return "High quality 1080p/native at 60 FPS may drop frames on slower CPUs. Use Performance quality or lower FPS/resolution.";
        }
        if (heavyResolution && fps >= 60) {
            return "If queue warnings appear, switch to 720p or 30 FPS for smoother recording.";
        }
        return "Current settings are expected to record smoothly on most systems.";
    }

    /**
     * Migrates legacy audio device values from older releases.
     *
     * @return true if migration changed config values
     */
    public boolean migrateOldConfig() {
        if (audioDevice != null && "openal".equalsIgnoreCase(audioDevice.trim())) {
            RecordableMod.LOGGER.info("Migrating legacy audioDevice='openal' to 'auto'.");
            audioDevice = "auto";
            return true;
        }
        return false;
    }

    public void sanitize() {
        if (format != null && (format.contains("recordable-") || format.contains(".wav") || format.length() > 10)) {
            RecordableMod.LOGGER.warn("Config format field was corrupted: '{}'. Resetting to 'mp4'.", format);
            format = "mp4";
        }
        format = sanitizeString(format, FORMATS, "mp4");
        resolution = sanitizeString(resolution, RESOLUTIONS, "native");
        quality = sanitizeString(quality, QUALITIES, "balanced");
        if (encoder == null) {
            encoder = VideoEncoder.SOFTWARE;
        }
        autoRecordTrigger = sanitizeString(autoRecordTrigger, AUTO_RECORD_TRIGGERS, "world_join");
        autoStopTrigger = sanitizeString(autoStopTrigger, AUTO_STOP_TRIGGERS, "world_leave");

        if (Arrays.stream(FPS_VALUES).noneMatch(value -> value == fps)) {
            fps = 60;
        }

        if (bitrate == null || bitrate.isBlank()) {
            bitrate = "auto";
        } else {
            String trimmed = bitrate.trim();
            bitrate = "auto".equalsIgnoreCase(trimmed) ? "auto" : trimmed;
            if (!"auto".equalsIgnoreCase(bitrate) && !bitrate.matches("(?i)^[1-9][0-9]*(?:[km])?$")) {
                bitrate = "auto";
            }
        }

        audioSource = "game";
        if (audioDevice == null || audioDevice.isBlank()) {
            audioDevice = "auto";
        } else {
            audioDevice = audioDevice.trim();
            if ("openal".equalsIgnoreCase(audioDevice)) {
                audioDevice = "auto";
            }
        }

        if (audioEncoder == null) {
            audioEncoder = AudioEncoder.AAC;
        }

        if (audioBitrate != null && !audioBitrate.isBlank()) {
            String trimmedAudio = audioBitrate.trim().toLowerCase(Locale.ROOT);
            if (trimmedAudio.matches("^[1-9][0-9]*k$")) {
                try {
                    audioBitrateKbps = Integer.parseInt(trimmedAudio.substring(0, trimmedAudio.length() - 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        audioBitrateKbps = Math.max(32, Math.min(512, audioBitrateKbps));

        audioChannelCount = (audioChannelCount == 1) ? 1 : 2;
        audioChannels = sanitizeString(audioChannels, AUDIO_CHANNELS, "auto");
        if ("mono".equals(audioChannels)) {
            audioChannelCount = 1;
        } else if ("stereo".equals(audioChannels)) {
            audioChannelCount = 2;
        }

        if (Arrays.stream(AUDIO_SAMPLE_RATES).noneMatch(value -> value == audioSampleRate)) {
            audioSampleRate = 48000;
        }

        audioVolume = Math.max(0, Math.min(200, audioVolume));
        audioVolumeBoostDb = Math.max(0, Math.min(24, audioVolumeBoostDb));

        if (microphoneDevice == null || microphoneDevice.isBlank()) {
            microphoneDevice = "auto";
        } else {
            microphoneDevice = microphoneDevice.trim();
        }
        gameAudioVolume = Math.max(0, Math.min(200, gameAudioVolume));
        microphoneVolume = Math.max(0, Math.min(200, microphoneVolume));

        validateAudioEncoderCompatibility();

        audioBitrate = audioBitrateKbps + "k";
        audioChannels = audioChannelCount == 1 ? "mono" : "stereo";

        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "recordings";
        } else {
            outputDir = outputDir.trim();
        }

        if (filenamePattern == null || filenamePattern.trim().isEmpty()) {
            filenamePattern = DEFAULT_FILENAME_PATTERN;
        } else {
            filenamePattern = filenamePattern.trim();
        }

        try {
            String detected = FfmpegBundleManager.getBundledFfmpegPath();
            bundledFfmpegPath = detected != null ? detected : "";
        } catch (Exception e) {
            bundledFfmpegPath = "";
        }

        overlayColor = sanitizeHexColor(overlayColor, "#FF0000");
        menuAccentColor = sanitizeHexColor(menuAccentColor, "#FF0000");
        if (overlayPosition == null) {
            overlayPosition = OverlayPosition.TOP_LEFT;
        }
        overlayScale = Math.max(50, Math.min(200, overlayScale));
        mousePointerScale = Math.max(50, Math.min(300, mousePointerScale));
        if (mousePointerTheme == null) {
            mousePointerTheme = MousePointerTheme.CLASSIC;
        }
        homeButtonX = Math.max(-1, Math.min(10000, homeButtonX));
        homeButtonY = Math.max(-1, Math.min(10000, homeButtonY));

        // On-screen VHS overlay
        if (overlayStyleHud == null) {
            overlayStyleHud = OverlayStyleHud.CLASSIC;
        }
        vhsPlayColor = sanitizeHexColor(vhsPlayColor, "#FFFFFF");
        vhsRecTextColor = sanitizeHexColor(vhsRecTextColor, "#FFFFFF");
        vhsRecDotColor = sanitizeHexColor(vhsRecDotColor, "#CC1E1E");
        vhsBracketColor = sanitizeHexColor(vhsBracketColor, "#C8FFFFFF");
        vhsTimestampColor = sanitizeHexColor(vhsTimestampColor, "#FFFFFF");
        vhsDateColor = sanitizeHexColor(vhsDateColor, "#FFFFFF");
        vhsSpColor = sanitizeHexColor(vhsSpColor, "#FFFFFF");

        // Overlay element positions
        hudPlayRecX = Math.max(0, Math.min(2000, hudPlayRecX));
        hudPlayRecY = Math.max(0, Math.min(2000, hudPlayRecY));
        hudTimestampOffsetX = Math.max(0, Math.min(2000, hudTimestampOffsetX));
        hudTimestampY = Math.max(0, Math.min(2000, hudTimestampY));
        hudSpX = Math.max(0, Math.min(2000, hudSpX));
        hudSpOffsetY = Math.max(0, Math.min(2000, hudSpOffsetY));
        hudPerfOffsetX = Math.max(0, Math.min(2000, hudPerfOffsetX));
        hudPerfOffsetY = Math.max(0, Math.min(2000, hudPerfOffsetY));
        hudDetailsOffsetX = Math.max(0, Math.min(2000, hudDetailsOffsetX));
        hudDetailsOffsetY = Math.max(0, Math.min(2000, hudDetailsOffsetY));
        hudCornersX = Math.max(0, Math.min(2000, hudCornersX));
        hudCornersY = Math.max(0, Math.min(2000, hudCornersY));
        hudCornersWidth = Math.max(20, Math.min(2000, hudCornersWidth));
        hudCornersHeight = Math.max(20, Math.min(2000, hudCornersHeight));

        // Element size overrides (0 = auto)
        hudPlayRecW = Math.max(0, Math.min(2000, hudPlayRecW));
        hudPlayRecH = Math.max(0, Math.min(2000, hudPlayRecH));
        hudTimestampW = Math.max(0, Math.min(2000, hudTimestampW));
        hudTimestampH = Math.max(0, Math.min(2000, hudTimestampH));
        hudSpW = Math.max(0, Math.min(2000, hudSpW));
        hudSpH = Math.max(0, Math.min(2000, hudSpH));
        hudPerfW = Math.max(0, Math.min(2000, hudPerfW));
        hudPerfH = Math.max(0, Math.min(2000, hudPerfH));
        hudDetailsW = Math.max(0, Math.min(2000, hudDetailsW));
        hudDetailsH = Math.max(0, Math.min(2000, hudDetailsH));

        // Per-element opacity
        hudPlayRecOpacity = Math.max(0, Math.min(100, hudPlayRecOpacity));
        hudTimestampOpacity = Math.max(0, Math.min(100, hudTimestampOpacity));
        hudCornersOpacity = Math.max(0, Math.min(100, hudCornersOpacity));
        hudSpOpacity = Math.max(0, Math.min(100, hudSpOpacity));
        hudDetailsOpacity = Math.max(0, Math.min(100, hudDetailsOpacity));
        hudPerfOpacity = Math.max(0, Math.min(100, hudPerfOpacity));
        hudMicOpacity = Math.max(0, Math.min(100, hudMicOpacity));
        if (hudMicX < -1) hudMicX = -1;
        if (hudMicY < 0) hudMicY = 0;

        // Layer order - keep only recognized layers, then append any newly-added
        // layers that are missing.
        java.util.List<String> allLayers = allLayerIds();
        java.util.Set<String> validLayers = new java.util.HashSet<>(allLayers);
        if (hudLayerOrder == null || hudLayerOrder.isBlank()) {
            hudLayerOrder = defaultLayerOrder();
        } else {
            StringBuilder cleaned = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String part : hudLayerOrder.split(",")) {
                String id = part.trim();
                if (!id.isEmpty() && validLayers.contains(id) && seen.add(id)) {
                    if (cleaned.length() > 0) cleaned.append(',');
                    cleaned.append(id);
                }
            }
            // Append any recognized layers that weren't present (forward-compat)
            for (String id : allLayers) {
                if (seen.add(id)) {
                    if (cleaned.length() > 0) cleaned.append(',');
                    cleaned.append(id);
                }
            }
            hudLayerOrder = cleaned.length() > 0 ? cleaned.toString() : defaultLayerOrder();
        }

        maxFileSizeMB = Math.max(0, maxFileSizeMB);
        autoRecordDelay = Math.max(0, Math.min(10, autoRecordDelay));

        // Sanitize audio delay preset
        if (audioDelayPreset == null) {
            audioDelayPreset = AudioDelayPreset.AUTO;
        }
        // Range -500 to 500: positive delays audio (audio early), negative advances audio (audio late).
        audioSyncOffsetMs = Math.max(-500, Math.min(500, audioSyncOffsetMs));

        // Sanitize new feature fields
        activeTemplate = sanitizeString(activeTemplate, TEMPLATES, "custom");
        diskSpaceWarnPercent = Math.max(50, Math.min(99, diskSpaceWarnPercent));
        diskSpaceBlockPercent = Math.max(diskSpaceWarnPercent + 1, Math.min(100, diskSpaceBlockPercent));
        diskSpaceMinFreeMB = Math.max(100, Math.min(10000, diskSpaceMinFreeMB));
        replayBufferDurationSeconds = Math.max(10, Math.min(600, replayBufferDurationSeconds));

        // Sanitize auto-clip settings
        autoClipDuration = Math.max(5, Math.min(300, autoClipDuration));
        autoClipKillPreSeconds = Math.max(0, Math.min(10, autoClipKillPreSeconds));
        autoClipKillPostSeconds = Math.max(0, Math.min(10, autoClipKillPostSeconds));
        autoClipHindsightLookbackSeconds = Math.max(5, Math.min(60, autoClipHindsightLookbackSeconds));
        // Snap the auto-clip FPS to the nearest supported value if an out-of-range value was
        // loaded from an older/edited config.
        if (Arrays.stream(AUTO_CLIP_FPS_VALUES).noneMatch(value -> value == autoClipFps)) {
            int nearest = AUTO_CLIP_FPS_VALUES[0];
            int bestDelta = Integer.MAX_VALUE;
            for (int candidate : AUTO_CLIP_FPS_VALUES) {
                int delta = Math.abs(candidate - autoClipFps);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    nearest = candidate;
                }
            }
            autoClipFps = nearest;
        }
        // A montage with no footage at all makes no sense; ensure at least 1s total.
        if (autoClipKillPreSeconds == 0 && autoClipKillPostSeconds == 0) {
            autoClipKillPreSeconds = 1;
            autoClipKillPostSeconds = 1;
        }

        // === V1-0.06: Replay Buffer (extended) ===
        replayBufferQuality = sanitizeString(replayBufferQuality, REPLAY_QUALITIES, "balanced");

        // === V1-0.06: Recording Gallery ===
        gallerySortMode = sanitizeString(gallerySortMode, GALLERY_SORT_MODES, "newest");
        galleryColumns = Math.max(1, Math.min(6, galleryColumns));

        // === V1-0.06: Watermark / Branding ===
        if (watermarkSlots == null) {
            watermarkSlots = new ArrayList<>();
        }
        // Drop excess slots and sanitize each remaining slot.
        while (watermarkSlots.size() > MAX_WATERMARK_SLOTS) {
            watermarkSlots.remove(watermarkSlots.size() - 1);
        }
        for (int i = watermarkSlots.size() - 1; i >= 0; i--) {
            WatermarkSlot slot = watermarkSlots.get(i);
            if (slot == null) {
                watermarkSlots.remove(i);
            } else {
                slot.sanitize();
            }
        }

        // === V1-0.06: Storage Manager ===
        autoCleanupOlderThanDays = Math.max(1, Math.min(3650, autoCleanupOlderThanDays));
        autoCleanupMaxTotalMB = Math.max(0, Math.min(10_000_000, autoCleanupMaxTotalMB));
        storageCompressionCrf = Math.max(0, Math.min(51, storageCompressionCrf));
        if (storageProtectedFiles == null) {
            storageProtectedFiles = new ArrayList<>();
        }

        // === V1-0.06: Performance Optimizer ===
        perfMinFps = Math.max(10, Math.min(240, perfMinFps));

        // === V1-0.08: Smooth Motion ===
        smoothMotionMode = SmoothMotion.sanitizeMode(smoothMotionMode);

        // === V1-0.08: Streamer Mode / censor regions ===
        if (streamerDefaultCensorStyle == null
                || !(streamerDefaultCensorStyle.equals("SOLID")
                    || streamerDefaultCensorStyle.equals("GRADIENT"))) {
            streamerDefaultCensorStyle = "SOLID";
        }
        if (censorRegions == null) {
            censorRegions = new ArrayList<>();
        } else {
            for (CensorRegion region : censorRegions) {
                if (region != null) {
                    region.sanitize();
                }
            }
        }

        // Sanitize UI theme settings
        if (uiTheme == null) {
            uiTheme = ThemePreset.VHS;
        }
        if (uiCustomAccentColor == null) {
            uiCustomAccentColor = "";
        } else if (!uiCustomAccentColor.isBlank()) {
            uiCustomAccentColor = sanitizeHexColor(uiCustomAccentColor, "");
        }
        uiGalaxyGlowIntensity = Math.max(0, Math.min(2, uiGalaxyGlowIntensity));

    }

    /**
     * Expands a user filename pattern into a concrete, filesystem-safe base name (no
     * extension). Supported tokens are {@code {datetime}} (yyyyMMdd-HHmmss),
     * {@code {date}} (yyyyMMdd) and {@code {time}} (HHmmss); everything else is kept
     * literally. Illegal filename characters and trailing dots/spaces are stripped. If
     * the pattern is blank or resolves to an empty string, the default pattern is used.
     */
    public static String resolveFilenamePattern(String pattern) {
        String p = (pattern == null || pattern.trim().isEmpty())
                ? DEFAULT_FILENAME_PATTERN : pattern.trim();

        LocalDateTime now = LocalDateTime.now();
        String datetime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = now.format(DateTimeFormatter.ofPattern("HHmmss"));

        String resolved = p
                .replace("{datetime}", datetime)
                .replace("{date}", date)
                .replace("{time}", time);

        // Strip characters that are illegal in filenames on common platforms.
        resolved = resolved.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "");
        // Collapse whitespace runs and trim trailing dots/spaces (illegal on Windows).
        resolved = resolved.replaceAll("\\s+", " ").trim();
        resolved = resolved.replaceAll("[ .]+$", "").trim();

        if (resolved.isEmpty()) {
            // Fall back to the default pattern, expanded the same way.
            resolved = DEFAULT_FILENAME_PATTERN
                    .replace("{datetime}", datetime)
                    .replace("{date}", date)
                    .replace("{time}", time);
        }
        return resolved;
    }

    /**
     * Applies a named recording profile, overwriting video/quality settings.
     * Does not change profile to "custom" - call this when user selects a preset.
     */
    public void applyTemplate(String templateName) {
        switch (templateName) {
            case "cinematic" -> {
                resolution = "1080p";
                fps = 60;
                quality = "high";
                bitrate = "auto";
                // NOTE: do NOT set `encoder` here. Profiles only define resolution/fps/
                // quality/bitrate; the user's hardware/software encoder choice (e.g. NVENC)
                // must be preserved when switching templates.
                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 256;
                maxFileSizeMB = 0; // unlimited
            }
            case "balanced" -> {
                resolution = "1080p";
                fps = 60;
                quality = "balanced";
                bitrate = "auto";
                // NOTE: do NOT set `encoder` here. Profiles only define resolution/fps/
                // quality/bitrate; the user's hardware/software encoder choice (e.g. NVENC)
                // must be preserved when switching templates.
                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 192;
                maxFileSizeMB = 0; // unlimited
            }
            case "pvp_clip" -> {
                resolution = "720p";
                fps = 60;
                quality = "balanced";
                bitrate = "auto";
                maxFileSizeMB = 200;
                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 128;
            }
            default -> {
                // "custom" - do nothing, user settings preserved
            }
        }
        activeTemplate = templateName;
        sanitize();
    }

    /**
     * Returns a human-readable name for the given template key.
     */
    public static String getTemplateDisplayName(String template) {
        return switch (template) {
            case "cinematic" -> "Cinematic";
            case "balanced" -> "Balanced";
            case "pvp_clip" -> "PvP Clip";
            default -> "Custom";
        };
    }

    /**
     * Returns a short description for the given template.
     */
    public static String getTemplateDescription(String template) {
        return switch (template) {
            case "cinematic" -> "1080p60, high quality, unlimited size";
            case "balanced" -> "1080p60, balanced quality, general use";
            case "pvp_clip" -> "720p60, quick clips under 200MB";
            default -> "Your custom settings";
        };
    }

    /**
     * Applies optimizations specifically for low-end devices (weak CPUs, integrated
     * GPUs, phones, tablets). This preset prioritizes maintaining smooth gameplay
     * framerate over recording quality.
     * 
     * <p><b>What this does:</b></p>
     * <ul>
     *   <li>Sets recording to 480p @ 30 FPS with "performance" quality preset</li>
     *   <li>Enables software encoder (most compatible, uses CPU instead of GPU)</li>
     *   <li>Reduces audio bitrate to 96 kbps mono</li>
     *   <li>Enables performance optimizer with auto-adjust</li>
     *   <li>Disables heavy UI effects (scanlines, film grain, glitches, vignette)</li>
     *   <li>Disables UI animations for instant response</li>
     *   <li>Sets overlay to NONE (removes in-game rendering overhead)</li>
     *   <li>Disables replay buffer (memory-intensive feature)</li>
     *   <li>Sets filter to NONE (no post-processing overhead)</li>
     * </ul>
     * 
     * <p>After applying this preset, you can fine-tune individual settings as needed.
     * For example, if your device handles 720p30 well, you can increase resolution
     * while keeping the other optimizations.</p>
     */
    public void applyLowEndOptimizations() {
        // Core recording settings - minimize CPU/GPU load
        resolution = "480p";
        fps = 30;
        quality = "performance";  // CRF 28, ultrafast preset
        bitrate = "auto";
        encoder = VideoEncoder.SOFTWARE;  // libx264, doesn't compete for GPU
        
        // Audio - reduce bitrate and use mono to save bandwidth
        captureAudio = true;
        audioEncoder = AudioEncoder.AAC;
        audioBitrateKbps = 96;
        audioChannels = "mono";
        audioSampleRate = 44100;  // Lower than 48000
        
        // Performance optimizer - enable with aggressive auto-adjust
        perfOptimizerEnabled = true;
        perfAutoAdjust = true;
        perfMinFps = 30;  // Lower target for low-end devices
        perfModeGamePriority = true;
        perfActionLowerRes = true;
        perfActionLowerFps = true;
        perfActionFasterPreset = true;
        perfWarnBeforeAdjust = false;  // Auto-apply without confirmation
        perfShowStatsOverlay = false;  // Stats overlay adds rendering overhead
        
        // UI effects - disable all for maximum performance
        uiScanlines = false;
        uiFilmGrain = false;
        uiGlitchEffects = false;
        uiVignette = false;
        uiAnimations = false;
        uiGalaxyGlowIntensity = 0;
        uiTheme = ThemePreset.MINIMAL;  // Lightest theme
        
        // Overlay - disable to remove in-game rendering overhead
        overlayStyleHud = OverlayStyleHud.NONE;
        
        // Replay buffer - disable (memory and CPU intensive)
        replayBufferEnabled = false;
        
        // Filters - disable all post-processing effects
        filterVhsVisible = false;
        filterLcdMoireVisible = false;
        filterCrtVisible = false;
        
        // Watermarks - disable for cleaner output and less rendering
        watermarksEnabled = false;
        
        // Auto-compress (Android) - keep enabled for smaller files
        // (no performance impact, happens after recording)
        
        activeTemplate = "custom";
        sanitize();
    }

    // ------------------------------------------------------------------
    // Device performance presets (lowest -> highest)
    // ------------------------------------------------------------------
    /** Ordered list of device preset keys, from lowest-end to highest-end. */
    public static final String[] DEVICE_PRESETS = {
        "android_phone", "low_end_pc", "mid_end_pc", "high_end_pc", "nasa"
    };

    /** Returns the preset if it is a known key, otherwise falls back to the default. */
    public static String sanitizeDevicePreset(String preset) {
        if (preset != null) {
            for (String p : DEVICE_PRESETS) {
                if (p.equals(preset)) return preset;
            }
        }
        return "mid_end_pc";
    }

    /** Human-friendly display name for a device preset key. */
    public static String getDevicePresetDisplayName(String preset) {
        if (preset == null) return "Custom";
        switch (preset) {
            case "android_phone": return "Android Phones";
            case "low_end_pc":    return "Low-end PC";
            case "mid_end_pc":    return "Mid-end PC";
            case "high_end_pc":   return "High-end PC";
            case "nasa":          return "N.A.S.A Super-Computer";
            default:              return "Custom";
        }
    }

    /**
     * Apply a one-click device performance preset that tunes ONLY hardware-performance
     * settings (recording resolution/fps/quality/bitrate/encoder, audio encoding and the
     * performance optimizer) to match the target hardware class.
     *
     * <p>It deliberately does NOT touch user-preference / feature toggles such as the
     * replay buffer, the on-screen overlay, watermarks or the cosmetic UI theme & filters.
     * Those are personal choices unrelated to how powerful the user's hardware is, so the
     * preset leaves whatever the user already configured untouched.</p>
     *
     * <p>Always ends with sanitize().</p>
     */
    public void applyDevicePreset(String preset) {
        if (preset == null) preset = "mid_end_pc";
        switch (preset) {
            case "android_phone": {
                // Lowest: phones / very weak hardware - minimise everything.
                resolution = "480p";
                fps = 30;
                quality = "performance";
                bitrate = "auto";
                encoder = VideoEncoder.SOFTWARE;

                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 96;
                audioChannels = "mono";
                audioSampleRate = 44100;

                perfOptimizerEnabled = true;
                perfAutoAdjust = true;
                perfMinFps = 30;
                perfModeGamePriority = true;
                perfActionLowerRes = true;
                perfActionLowerFps = true;
                perfActionFasterPreset = true;
                perfWarnBeforeAdjust = false;
                break;
            }
            case "low_end_pc": {
                // Low: weak desktops/laptops - software encode, keep it light.
                resolution = "720p";
                fps = 30;
                quality = "performance";
                bitrate = "auto";
                encoder = VideoEncoder.SOFTWARE;

                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 128;
                audioChannels = "stereo";
                audioSampleRate = 44100;

                perfOptimizerEnabled = true;
                perfAutoAdjust = true;
                perfMinFps = 30;
                perfModeGamePriority = true;
                perfActionLowerRes = true;
                perfActionLowerFps = true;
                perfActionFasterPreset = true;
                perfWarnBeforeAdjust = false;
                break;
            }
            case "high_end_pc": {
                // High: strong gaming rigs - hardware encode, high quality.
                resolution = "1080p";
                fps = 60;
                quality = "high";
                bitrate = "auto";
                encoder = VideoEncoder.NVIDIA;

                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 256;
                audioChannels = "stereo";
                audioSampleRate = 48000;

                perfOptimizerEnabled = false;
                perfAutoAdjust = false;
                break;
            }
            case "nasa": {
                // Highest: no-compromise workstation - max everything.
                resolution = "native";
                fps = 120;
                quality = "high";
                bitrate = "auto";
                encoder = VideoEncoder.NVIDIA;

                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 320;
                audioChannels = "stereo";
                audioSampleRate = 48000;

                perfOptimizerEnabled = false;
                perfAutoAdjust = false;
                break;
            }
            case "mid_end_pc":
            default: {
                // Mid: typical gaming PC - balanced, hardware encode with optimizer safety net.
                resolution = "1080p";
                fps = 60;
                quality = "balanced";
                bitrate = "auto";
                encoder = VideoEncoder.NVIDIA;

                captureAudio = true;
                audioEncoder = AudioEncoder.AAC;
                audioBitrateKbps = 192;
                audioChannels = "stereo";
                audioSampleRate = 48000;

                perfOptimizerEnabled = true;
                perfAutoAdjust = true;
                perfMinFps = 45;
                perfModeGamePriority = true;
                perfActionLowerRes = false;
                perfActionLowerFps = true;
                perfActionFasterPreset = true;
                perfWarnBeforeAdjust = true;
                break;
            }
        }

        activeTemplate = "custom";
        sanitize();
    }

    public void validateAudioEncoderCompatibility() {
        if (audioEncoder == null) {
            audioEncoder = AudioEncoder.AAC;
        }

        String container = getContainerFromFormat();
        if (!audioEncoder.supportsContainer(container)) {
            // WebM only accepts Opus/Vorbis, so AAC is NOT a valid fallback there; pick the
            // container-appropriate default instead (Opus for WebM, AAC for everything else).
            AudioEncoder fallback = "webm".equals(container) ? AudioEncoder.OPUS : AudioEncoder.AAC;
            RecordableMod.LOGGER.warn("Audio encoder {} is not supported in {} container. Falling back to {}.",
                    audioEncoder.displayName,
                    container,
                    fallback.displayName);
            audioEncoder = fallback;
        }

        if (!audioEncoder.isLossless() && audioBitrateKbps <= 0) {
            audioBitrateKbps = Math.max(96, audioEncoder.defaultBitrateKbps);
        }
    }

    public String getContainerFromFormat() {
        return switch (getFormat()) {
            case "webm" -> "webm";
            case "avi" -> "avi";
            case "mkv" -> "mkv";
            case "mov" -> "mov";
            default -> "mp4";
        };
    }

    /** True when the selected output format is the VP9/WebM container. */
    public boolean isWebmFormat() {
        return "webm".equals(getFormat());
    }

    /**
     * Resolves a replay-quality preset into a target {@code {height, fps}} for the replay buffer.
     *
     * <p>The returned height of {@code 0} means "use the recording resolution unchanged"
     * (the {@code source} preset). All values are clamped so the replay never requests a
     * higher resolution or frame rate than the live recording actually produces.</p>
     *
     * @param quality  one of {@link #REPLAY_QUALITIES}
     * @param recHeight recording height in pixels
     * @param recFps    recording frames per second
     * @return a two-element array {@code {targetHeight, targetFps}}
     */
    public static int[] resolveReplayPreset(String quality, int recHeight, int recFps) {
        String q = quality == null ? "source" : quality.trim().toLowerCase(Locale.ROOT);
        int safeFps = recFps > 0 ? recFps : 30;
        switch (q) {
            case "balanced":
                return new int[]{recHeight > 0 ? Math.min(720, recHeight) : 720, Math.min(30, safeFps)};
            case "performance":
                return new int[]{recHeight > 0 ? Math.min(480, recHeight) : 480, Math.min(30, safeFps)};
            case "high":
                return new int[]{recHeight > 0 ? Math.min(1080, recHeight) : 1080, Math.min(60, safeFps)};
            case "source":
            default:
                return new int[]{0, safeFps};
        }
    }

    private static String sanitizeString(String value, String[] allowed, String fallback) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (String candidate : allowed) {
                if (candidate.equals(normalized)) {
                    return normalized;
                }
            }
        }
        return fallback;
    }

    private static String sanitizeHexColor(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        if (!HEX_COLOR_PATTERN.matcher(normalized).matches()) {
            return fallback;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static int parseHexColor(String value, int fallback) {
        String normalized = sanitizeHexColor(value, String.format(Locale.ROOT, "#%06X", fallback));
        try {
            // Use Long.parseLong + cast rather than Integer.parseInt: an 8-digit
            // AARRGGBB value with the alpha byte's high bit set (e.g. "#C8FFFFFF")
            // exceeds Integer.MAX_VALUE and Integer.parseInt throws
            // NumberFormatException for it, silently discarding a valid color.
            return (int) Long.parseLong(normalized.substring(1), 16);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /**
     * Resolves which encoder backend to use. FFmpeg is the only supported encoder.
     */
    public EncoderType resolveEncoderType() {
        return EncoderType.FFMPEG;
    }

    private static int makeEven(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    public record CaptureDimensions(int width, int height) {
    }
}
