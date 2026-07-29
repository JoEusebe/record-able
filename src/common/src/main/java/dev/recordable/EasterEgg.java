package dev.recordable;

import java.util.UUID;

/**
 * Shared, render-free state and timing helper for the hidden "iskasaisora"
 * easter egg. Kept free of any Minecraft GUI imports so it can live in the
 * common module and be reused by all three variants (legacy, sandwich, modern).
 *
 * <p>Behaviour is gated by {@link RecordableConfig#iskasaisora} OR by the
 * current player's UUID matching one of the hardcoded accounts. When both
 * are false every method here is inert, so normal users never notice anything.</p>
 */
public final class EasterEgg {

    /** How long the white screen flash takes to fade out, in milliseconds. */
    private static final long FLASH_DURATION_MS = 700L;

    /** Timestamp (ms) when the flash was last triggered, or 0 if never/none active. */
    private static volatile long flashStart = 0L;

    /**
     * Hardcoded UUIDs that automatically trigger the easter egg regardless of
     * the config flag (fallback for internal failures or testing).
     */
    private static final UUID KASAISORA_UUID = UUID.fromString("916b1144-e589-4af7-8d4b-79aab81f614f");
    private static final UUID BREADSHOP_UUID = UUID.fromString("1cb42cb9-f76a-4762-bf44-61c8217002f9");
    private static final UUID OMNIVERSIAL_UUID = UUID.fromString("24eecfec-a707-4d9a-9700-86cb4084487c");
    private static final UUID PARTISAN_UUID = UUID.fromString("b0fb1f1b-3ec9-43a2-9bea-95c77a91e8ab");

    private EasterEgg() {
    }

    /**
     * Returns true when the easter egg is switched on via the config flag
     * OR when the current player's UUID matches one of the hardcoded accounts.
     * Any failure to read the config or player UUID is treated as "disabled".
     *
     * @param playerUUID the current player's UUID (may be null)
     */
    public static boolean enabled(UUID playerUUID) {
        try {
            RecordableConfig config = RecordableConfig.get();
            // Permanent kill switch: once the user says bye to Lilly, the egg is
            // fully off for everyone (including the fallback accounts) until the
            // config JSON is hand-edited back to "iskasaisoraOff": false.
            if (config != null && config.iskasaisoraOff) {
                return false;
            }
            if (config != null && config.iskasaisora) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (playerUUID != null) {
            return KASAISORA_UUID.equals(playerUUID) 
                    || BREADSHOP_UUID.equals(playerUUID) 
                    || OMNIVERSIAL_UUID.equals(playerUUID)
                    || PARTISAN_UUID.equals(playerUUID);
        }
        return false;
    }

    /**
     * Convenience overload that returns true only when the config flag is set.
     * Use {@link #enabled(UUID)} when you have access to the player UUID.
     */
    public static boolean enabled() {
        return enabled(null);
    }

    /** Number of thank-you-screen opens after which the guide arrows stop showing. */
    private static final int MAX_ARROW_SHOWS = 2;

    /** Starts (or restarts) the white screen flash animation. */
    public static void triggerFlash() {
        flashStart = System.currentTimeMillis();
    }

    /** True while the white flash is still visible (non-zero alpha). */
    public static boolean flashActive() {
        return flashAlpha() > 0;
    }

    /** Reads how many times the thank-you screen has been opened. */
    public static int timesSeen() {
        try {
            RecordableConfig config = RecordableConfig.get();
            return config != null ? config.iskasaisoraSeen : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Records one more thank-you-screen open and persists it, so the guide arrows
     * can stop appearing after {@link #MAX_ARROW_SHOWS} views.
     */
    public static void markSeen() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null) {
                config.iskasaisoraSeen = config.iskasaisoraSeen + 1;
                config.save();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * True when the red guide arrows should still be drawn: the egg is enabled,
     * the opening white flash has finished, and the screen has been opened fewer
     * than {@link #MAX_ARROW_SHOWS} times.
     *
     * @param playerUUID the current player's UUID (may be null)
     */
    public static boolean shouldShowArrows(UUID playerUUID) {
        return enabled(playerUUID) && !flashActive() && timesSeen() < MAX_ARROW_SHOWS;
    }

    /**
     * Returns the current white-flash opacity as an alpha value in the range
     * 0-255. Returns 0 when no flash is active or once the flash has fully faded.
     * The flash starts fully opaque and eases out over {@link #FLASH_DURATION_MS}.
     */
    public static int flashAlpha() {
        long start = flashStart;
        if (start == 0L) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed < 0L || elapsed >= FLASH_DURATION_MS) {
            flashStart = 0L;
            return 0;
        }
        double t = (double) elapsed / (double) FLASH_DURATION_MS; // 0..1
        // Ease-out (quadratic) so it lingers bright then fades quickly at the end.
        double remaining = 1.0 - (t * t);
        int alpha = (int) Math.round(remaining * 255.0);
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return alpha;
    }

    /**
     * Packs the given 0-255 alpha into an ARGB white colour (0xAARRGGBB) suitable
     * for a full-screen fill. Returns 0 (fully transparent) when alpha is 0.
     */
    public static int whiteFlashColor() {
        int alpha = flashAlpha();
        if (alpha <= 0) {
            return 0;
        }
        return (alpha << 24) | 0x00FFFFFF;
    }
}
