package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Compatibility bridge for timeline/replay recording mods such as Flashback and Replay Mod.
 *
 * <p>Historically these were treated as hard conflicts because Flashback (and, to a lesser
 * extent, Replay Mod) hook the very same OpenAL loopback device that Record-able uses for
 * game-audio capture, so only one mod could own the device and the other ended up silent.
 * This bridge replaces that "pick one mod" advice with two cooperative behaviours:</p>
 *
 * <ol>
 *   <li><b>Coexistence (audio yield).</b> When a replay mod is present and the bridge is
 *       enabled, Record-able voluntarily yields the OpenAL loopback device (letting the other
 *       mod keep working) and falls back to system-loopback / video-only audio for its own
 *       recordings. This avoids the device-ownership race that produced silent recordings.</li>
 *   <li><b>Playback auto-record.</b> When the bridge detects that a replay/flashback timeline
 *       is being played back or rendered, it can automatically start a Record-able screen
 *       recording and stop it again when playback ends, so you get a finished video file of
 *       the cinematic without manually toggling recording.</li>
 * </ol>
 *
 * <p>Both replay mods are <i>optional, unrelated</i> mods with no public API, so all detection
 * is done defensively (mod-id presence checks and class-name inspection of the active
 * camera/player/server objects). Every method fails safe: any error or missing class results
 * in "no replay mod / not playing", never an exception that could disrupt the game.</p>
 */
public final class ReplayCompatBridge {

    /** Known Fabric mod ids for supported replay/timeline recording mods. */
    private static final String[] REPLAY_MOD_IDS = {"flashback", "replaymod", "replay-mod"};

    /** Lower-cased class-name fragments that identify an object owned by a replay mod. */
    private static final String[] REPLAY_CLASS_HINTS = {"flashback", "replaymod"};

    /**
     * Bridge between the common code and a variant's recording manager. Each variant installs
     * one of these so the bridge can drive recording without depending on the variant's
     * platform-specific Minecraft mappings.
     */
    public interface RecordingController {
        /** @return true if Record-able is currently recording. */
        boolean isRecording();

        /** Start a recording for the given platform client object. */
        void startRecording(Object client);

        /** Stop the current recording for the given platform client object. */
        void stopRecording(Object client);
    }

    private static volatile RecordingController controller;

    /** Cached result of the (immutable for a session) replay-mod presence check. */
    private static volatile Boolean replayModPresent;

    // --- playback auto-record transition state ---
    private static volatile boolean playbackActiveLast = false;
    /** True when the current recording was started by this bridge (so we own stopping it). */
    private static volatile boolean recordingStartedByBridge = false;

    private ReplayCompatBridge() {}

    /** Installs the variant-specific recording controller. Called once during init. */
    public static void setRecordingController(RecordingController c) {
        controller = c;
    }

    /**
     * @return true if at least one supported replay mod is loaded. Cached after first call.
     */
    public static boolean isReplayModPresent() {
        Boolean cached = replayModPresent;
        if (cached != null) {
            return cached;
        }
        boolean present = false;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            for (String id : REPLAY_MOD_IDS) {
                if (loader.isModLoaded(id)) {
                    present = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // Fail safe: treat as not present.
        }
        replayModPresent = present;
        return present;
    }

    /** @return the id of the first present replay mod, or {@code null} if none. */
    public static String getPresentReplayModId() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            for (String id : REPLAY_MOD_IDS) {
                if (loader.isModLoaded(id)) {
                    return id;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** @return a friendly display name for the present replay mod (for chat/UI). */
    public static String getPresentReplayModName() {
        String id = getPresentReplayModId();
        if (id == null) {
            return "another recording mod";
        }
        switch (id) {
            case "flashback":            return "Flashback";
            case "replaymod":
            case "replay-mod":           return "Replay Mod";
            default:                      return id;
        }
    }

    /**
     * @return true if Record-able should yield the OpenAL loopback device to a replay mod
     *         instead of competing for it. Supports three modes:
     *         - If bridge is disabled: always false
     *         - If auto mode (replayYieldAudioDevice == null): true only if Flashback is present
     *         - If explicit mode (boolean): use the configured value
     *         Fails safe to {@code false} (keep normal loopback behaviour).
     */
    public static boolean shouldYieldAudioDevice() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null || !config.replayCompatBridge) {
                return false;
            }
            
            // Auto mode: yield if Flashback (but not Replay Mod) is detected
            if (config.replayYieldAudioDevice == null) {
                // Flashback is the primary conflict; auto-yield only for it
                String modId = getPresentReplayModId();
                return "flashback".equalsIgnoreCase(modId);
            }
            
            // Explicit mode: use the configured boolean value
            if (!config.replayYieldAudioDevice || !isReplayModPresent()) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Builds the (mapping-agnostic) "managed coexistence" status message shown in chat when a
     * replay mod is present and the bridge is enabled. Wording adapts to whether the audio
     * device is being yielded.
     */
    public static String getCoexistenceMessage() {
        String name = getPresentReplayModName();
        boolean yield = false;
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null) {
                // Auto mode: yield only if Flashback
                if (config.replayYieldAudioDevice == null) {
                    yield = "flashback".equalsIgnoreCase(getPresentReplayModId());
                } else {
                    yield = config.replayYieldAudioDevice;
                }
            }
        } catch (Throwable ignored) {
        }
        if (yield) {
            return "\u00a7a[Record-able] \u00a77Compatibility mode active with " + name
                    + ": yielding game-audio capture to it (Record-able uses system audio); "
                    + "replay playback can auto-record.";
        }
        return "\u00a7a[Record-able] \u00a77Compatibility mode active with " + name
                + ": replay playback can auto-record. Record-able keeps its own game audio.";
    }

    /**
     * Defensive check: walks an object's class hierarchy looking for a class owned by a replay
     * mod (package/name contains "flashback" or "replaymod"). Used to detect when the active
     * camera/player/integrated-server belongs to a replay mod during playback.
     *
     * @param o any object (typically a camera entity, player, or integrated server); may be null
     * @return true if the object's type appears to come from a replay mod
     */
    public static boolean isReplayClass(Object o) {
        if (o == null) {
            return false;
        }
        try {
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                String name = c.getName().toLowerCase();
                for (String hint : REPLAY_CLASS_HINTS) {
                    if (name.contains(hint)) {
                        return true;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Drives the playback auto-record state machine. Variants call this every client tick with
     * the platform client object and whether a replay/flashback timeline is currently being
     * played back or rendered (computed via the variant's mapped client, see
     * {@code ModCompatibilityChecker.detectReplayPlayback}).
     *
     * <p>On a rising edge (playback just started) it starts a recording if one is not already
     * running. On a falling edge (playback ended) it stops the recording only if this bridge
     * was the one that started it, so it never interrupts a recording the user started by hand.</p>
     */
    public static void onClientTick(Object client, boolean playbackActive) {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null || !config.replayCompatBridge || !config.replayAutoRecordPlayback) {
                // Feature off: keep edge state in sync so we never auto-stop on re-enable.
                playbackActiveLast = playbackActive;
                return;
            }
            RecordingController ctrl = controller;
            if (ctrl == null) {
                return;
            }

            if (playbackActive && !playbackActiveLast) {
                // Playback just started.
                if (!ctrl.isRecording()) {
                    ctrl.startRecording(client);
                    recordingStartedByBridge = true;
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                            "\u00a7a\u25cf Auto-recording " + getPresentReplayModName()
                                    + " playback (Record-able compatibility bridge).", true);
                }
            } else if (!playbackActive && playbackActiveLast) {
                // Playback just ended.
                if (recordingStartedByBridge && ctrl.isRecording()) {
                    ctrl.stopRecording(client);
                }
                recordingStartedByBridge = false;
            }
            playbackActiveLast = playbackActive;
        } catch (Throwable t) {
            // Never let compatibility logic disrupt the client tick.
            RecordableMod.LOGGER.debug("ReplayCompatBridge tick failed", t);
        }
    }
}
