package dev.recordable;

/**
 * Stub for Android MediaCodec hardware-accelerated video encoding.
 *
 * <p>On Android/PojavLauncher, the system provides hardware H.264 encoders
 * via the {@code android.media.MediaCodec} API that are 30-40× faster than
 * pure-Java encoding. This class acts as a placeholder for that integration.</p>
 *
 * <p><b>Current status:</b> Not yet implemented. Android detection is handled
 * by {@link PlatformUtils#detectPlatform()}, and when a future version adds
 * MediaCodec support, this class will be the entry point.</p>
 *
 * <p>For now, Android users will use the FFmpeg encoder (which still works
 * on PojavLauncher since {@code javax.imageio} is available on Android's
 * Java runtime).</p>
 */
public final class MediaCodecEncoder {

    private MediaCodecEncoder() {
    }

    /**
     * Returns {@code true} if Android MediaCodec hardware encoding is available.
     * Currently always returns {@code false} since this is a stub.
     */
    public static boolean isAvailable() {
        if (PlatformUtils.detectPlatform() != PlatformUtils.Platform.ANDROID) {
            return false;
        }

        try {
            Class.forName("android.media.MediaCodec");
            RecordableMod.LOGGER.info("Android MediaCodec API detected - hardware encoding available (future feature).");
            return false; // Stub: not yet implemented
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns a human-readable status string for the settings UI.
     */
    public static String getStatus() {
        if (PlatformUtils.detectPlatform() == PlatformUtils.Platform.ANDROID) {
            return "Android detected - MediaCodec support coming soon";
        }
        return "Not available (desktop platform)";
    }
}
