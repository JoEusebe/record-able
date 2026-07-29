package dev.recordable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and cross-platform bridge for Record-able mod.
 *
 * <p>This stub lives in the {@code common} source set and provides the logger,
 * mod ID, and a messaging bridge that are referenced by all shared utility
 * classes. Each platform subproject (legacy / modern) has its own full
 * {@code RecordableModInit} class that implements {@code ClientModInitializer}
 * and performs the actual initialization.</p>
 */
public class RecordableMod {
    public static final String MOD_ID = "recordable";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ── AutoClipManager bridge ─────────────────────────────────────────────

    private static volatile Object autoClipManagerInstance;

    @SuppressWarnings("unchecked")
    public static <T> T getAutoClipManager() {
        return (T) autoClipManagerInstance;
    }

    public static void setAutoClipManager(Object manager) {
        autoClipManagerInstance = manager;
    }

    // ── Client messaging bridge ────────────────────────────────────────────
    // Platform subprojects install their own implementation at init time.
    // This allows shared code (RecordingManager, ReplayBuffer, etc.) to send
    // in-game chat messages without importing net.minecraft.* classes.

    @FunctionalInterface
    public interface ClientMessageSender {
        void send(Object client, String message, boolean actionBar);
    }

    private static volatile ClientMessageSender messageSender;

    /** Called by platform init to install the messaging implementation. */
    public static void setMessageSender(ClientMessageSender sender) {
        messageSender = sender;
    }

    /**
     * Sends an in-game client message. The {@code client} parameter is the
     * platform-specific Minecraft client object (MinecraftClient or Minecraft).
     */
    public static void sendClientMessage(Object client, String message, boolean actionBar) {
        sendClientMessageInternal(client, message, actionBar);
    }

    /**
     * Category-aware overload. Suppresses the message if the corresponding "Chat Notifications"
     * toggle is disabled. Fails open: if the config cannot be read for any reason, the message
     * is still sent.
     */
    public static void sendClientMessage(ChatCategory category, Object client, String message, boolean actionBar) {
        if (!shouldNotify(category)) {
            return;
        }
        sendClientMessageInternal(client, message, actionBar);
    }

    /**
     * Returns whether messages of the given category should currently be shown, based on the
     * per-category chat notification toggles in the config. Fails open (returns true) if the
     * config is unavailable or any error occurs.
     */
    public static boolean shouldNotify(ChatCategory category) {
        if (category == null) {
            return true;
        }
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null) {
                return true;
            }
            switch (category) {
                case RECORDING:     return config.notifyRecording;
                case CLIPS:         return config.notifyClips;
                case REPLAY_BUFFER: return config.notifyReplayBuffer;
                case AUTO_RECORD:   return config.notifyAutoRecord;
                case BOOKMARKS:     return config.notifyBookmarks;
                case WARNINGS:      return config.notifyWarnings;
                case GENERAL:
                default:            return true;
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private static void sendClientMessageInternal(Object client, String message, boolean actionBar) {
        ClientMessageSender sender = messageSender;
        if (sender != null) {
            try {
                sender.send(client, message, actionBar);
            } catch (Throwable t) {
                LOGGER.warn("Failed to send client message: {}", message, t);
            }
        } else {
            LOGGER.info("[chat] {}", message);
        }
    }
}
