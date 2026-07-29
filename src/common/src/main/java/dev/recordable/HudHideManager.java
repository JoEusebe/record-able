package dev.recordable;

/**
 * Manages visibility state of HUD elements for clean recording mode.
 * When recording is active and clean recording options are enabled,
 * this manager tracks which UI elements should be hidden.
 */
public final class HudHideManager {
    private static boolean isRecording = false;
    
    /**
     * Called when recording starts.
     */
    public static void onRecordingStart() {
        isRecording = true;
    }
    
    /**
     * Called when recording stops.
     */
    public static void onRecordingStop() {
        isRecording = false;
    }
    
    /**
     * Check if chat should be hidden right now.
     */
    public static boolean shouldHideChat() {
        return isRecording && RecordableConfig.get().hideChat;
    }
    
    /**
     * Check if crosshair should be hidden right now.
     */
    public static boolean shouldHideCrosshair() {
        return isRecording && RecordableConfig.get().hideCrosshair;
    }
    
    /**
     * Check if hotbar should be hidden right now.
     */
    public static boolean shouldHideHotbar() {
        return isRecording && RecordableConfig.get().hideHotbar;
    }
    
    /**
     * Check if boss bar should be hidden right now.
     */
    public static boolean shouldHideBossBar() {
        return isRecording && RecordableConfig.get().hideBossBar;
    }
    
    /**
     * Check if player hand should be hidden right now.
     */
    public static boolean shouldHideHand() {
        return isRecording && RecordableConfig.get().hideHand;
    }
    
    /**
     * Check if scoreboard should be hidden right now.
     */
    public static boolean shouldHideScoreboard() {
        return isRecording && RecordableConfig.get().hideScoreboard;
    }
    
    /**
     * Check if vignette should be hidden right now.
     */
    public static boolean shouldHideVignette() {
        return isRecording && RecordableConfig.get().hideVignette;
    }
    
    /**
     * Check if ANY clean recording features are enabled.
     */
    public static boolean isAnyCleanRecordingEnabled() {
        RecordableConfig cfg = RecordableConfig.get();
        return cfg.hideChat || cfg.hideCrosshair || cfg.hideHotbar 
            || cfg.hideBossBar || cfg.hideHand || cfg.hideScoreboard || cfg.hideVignette;
    }
}
