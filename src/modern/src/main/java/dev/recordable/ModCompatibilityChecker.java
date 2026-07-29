package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects mods that are known to conflict with Record-able's audio/video
 * capture hooks at startup and when a recording begins.
 *
 * <p>Conflicts are logged as warnings and, optionally, surfaced as in-game
 * chat messages so the user can take action before recording artefacts occur.</p>
 */
public final class ModCompatibilityChecker {

    /**
     * Registry of known conflicting mod IDs → human-readable reason.
     * Add new entries here as conflicts are discovered.
     */
    private static final Map<String, String> KNOWN_CONFLICTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("flashback",
                "Flashback ALSO redirects Minecraft's OpenAL sound engine to capture game audio via " +
                "loopback - exactly the same hook Record-able uses. When both are installed only one " +
                "can own the loopback device, so Record-able may capture NO in-game audio (recordings " +
                "end up silent or contain only background/system noise). For working game-audio capture, " +
                "run only ONE recording mod at a time.");
        m.put("replaymod",
                "ReplayMod intercepts client packets and rendering, which can interfere with " +
                "Record-able's screen capture and audio recording.");
        m.put("replay-mod",
                "Replay Mod (alternate ID) may conflict with Record-able's capture hooks.");
        m.put("bettershields",
                "BetterShields has a known NPE crash when rendering shields in item frames " +
                "(getCurrentRenderedPlayer() returns null). This crash kills the game while " +
                "recording and may corrupt the current video file.");
        KNOWN_CONFLICTS = Collections.unmodifiableMap(m);
    }

    /** Cached list of detected conflicts (populated once at startup). */
    private static List<String> detectedConflicts;

    private ModCompatibilityChecker() {
    }

    /**
     * Scans the loaded mod list for known conflicts and logs warnings.
     * Call once during {@link RecordableMod#onInitializeClient()}.
     *
     * @return an unmodifiable list of human-readable conflict descriptions
     */
    public static List<String> checkAndLog() {
        List<String> conflicts = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();

        for (Map.Entry<String, String> entry : KNOWN_CONFLICTS.entrySet()) {
            if (loader.isModLoaded(entry.getKey())) {
                String msg = "[Record-able] Mod conflict detected: '" + entry.getKey() + "' - " + entry.getValue();
                RecordableMod.LOGGER.warn(msg);
                conflicts.add(msg);
            }
        }

        if (conflicts.isEmpty()) {
            RecordableMod.LOGGER.info("[Record-able] No known mod conflicts detected.");
        } else {
            RecordableMod.LOGGER.warn("[Record-able] {} conflicting mod(s) detected. " +
                    "Recording may be unstable - see warnings above.", conflicts.size());
        }

        detectedConflicts = Collections.unmodifiableList(conflicts);
        return detectedConflicts;
    }

    /**
     * Returns {@code true} if at least one conflicting mod was detected at startup.
     */
    public static boolean hasConflicts() {
        return detectedConflicts != null && !detectedConflicts.isEmpty();
    }

    /**
     * Returns the cached list of conflict descriptions (empty if none).
     */
    public static List<String> getDetectedConflicts() {
        return detectedConflicts != null ? detectedConflicts : Collections.emptyList();
    }

    /**
     * Displays an in-game warning to the player when a recording is about to start
     * and conflicts exist. Call this from {@link RecordingManager} or the toggle-key handler.
     *
     * @param client the active {@link Minecraft} instance
     */
    public static void warnPlayerIfConflicts(Minecraft client) {
        if (!hasConflicts() || client == null || client.player == null) {
            return;
        }

        if (!RecordableConfig.get().notifyWarnings) {
            return;
        }

        boolean bridge = false;
        try {
            bridge = RecordableConfig.get().replayCompatBridge;
        } catch (Throwable ignored) {
        }

        List<String> hardConflicts = new ArrayList<>();
        boolean replayCoexisting = false;
        for (String conflict : detectedConflicts) {
            if (bridge && isReplayConflict(conflict)) {
                replayCoexisting = true;
            } else {
                hardConflicts.add(conflict);
            }
        }

        // Replay mods are handled cooperatively by the compatibility bridge: show a calm note
        // instead of the scary "conflict" block.
        if (replayCoexisting) {
            client.player.sendSystemMessage(Component.literal(ReplayCompatBridge.getCoexistenceMessage()));
        }

        if (hardConflicts.isEmpty()) {
            return;
        }

        client.player.sendSystemMessage(Component.literal("§e[Record-able] §cWarning: conflicting mod(s) detected!"));
        for (String conflict : hardConflicts) {
            String brief = conflict.replace("[Record-able] Mod conflict detected: ", "");
            client.player.sendSystemMessage(Component.literal("§7  • " + brief));
        }
        client.player.sendSystemMessage(Component.literal("§eRecording may have A/V sync issues or instability. " +
                        "Consider disabling the conflicting mod(s)."));
    }

    /** @return true if the conflict message refers to a replay/timeline recording mod. */
    private static boolean isReplayConflict(String conflict) {
        if (conflict == null) {
            return false;
        }
        String c = conflict.toLowerCase();
        return c.contains("'flashback'") || c.contains("'replaymod'") || c.contains("'replay-mod'");
    }

    /**
     * Defensive detection of whether a replay/flashback timeline is currently being played
     * back or rendered, by inspecting the active camera entity, player, and integrated server
     * for classes owned by a replay mod. Fails safe to {@code false}.
     */
    public static boolean detectReplayPlayback(Minecraft client) {
        try {
            if (client == null) {
                return false;
            }
            if (ReplayCompatBridge.isReplayClass(client.getCameraEntity())) {
                return true;
            }
            if (ReplayCompatBridge.isReplayClass(client.player)) {
                return true;
            }
            if (ReplayCompatBridge.isReplayClass(client.getSingleplayerServer())) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
