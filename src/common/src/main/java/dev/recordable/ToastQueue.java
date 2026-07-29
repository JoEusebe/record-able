package dev.recordable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Version-agnostic queue that backs the custom Record-able toast notifications.
 *
 * <p>This class holds no Minecraft references, so it lives in the {@code common}
 * source set and is shared by all three Fabric variants (legacy / sandwich /
 * modern). Each variant renders the queued entries with its own themed drawing
 * routine (see {@code RecordingOverlay.renderToastNotification}), reproducing the
 * look from the mod's design mockups: the Record-able logo badge, a gray themed
 * panel with a dark border, and yellow (lime) text.</p>
 *
 * <p>All notifications previously shown as vanilla chat / action-bar messages are
 * now routed here through {@link RecordableMod#sendClientMessage(Object, String, boolean)},
 * so the entire mod uses a single, consistent toast style.</p>
 */
public final class ToastQueue {

    /** Default lifetime of a toast in milliseconds. */
    public static final long DEFAULT_DURATION_MS = 5000L;
    /** Slide-in / fade-in animation length in milliseconds. */
    public static final long ANIM_IN_MS = 340L;
    /** Fade-out animation length in milliseconds. */
    public static final long ANIM_OUT_MS = 420L;
    /** Maximum number of toasts shown at once (older ones expire first). */
    public static final int MAX_VISIBLE = 4;

    /** A single queued toast entry. */
    public static final class Entry {
        public final String message;
        public final long createdAtMs;
        public final long durationMs;

        Entry(String message, long createdAtMs, long durationMs) {
            this.message = message;
            this.createdAtMs = createdAtMs;
            this.durationMs = durationMs;
        }

        /** Total on-screen lifetime including fade-out. */
        public long totalLifeMs() {
            return durationMs + ANIM_OUT_MS;
        }

        public boolean isExpired(long now) {
            return now - createdAtMs >= totalLifeMs();
        }

        /**
         * Returns the current opacity factor (0..1) for this entry based on its
         * age. The entry fades in, then stays fully opaque; it disappears by
         * sliding off the right edge (see {@link #slideOutProgress(long)}) rather
         * than by fading, so alpha stays at 1 during the exit animation.
         */
        public float alpha(long now) {
            long age = now - createdAtMs;
            if (age < 0) return 0f;
            if (age < ANIM_IN_MS) {
                return clamp01(age / (float) ANIM_IN_MS);
            }
            return 1f;
        }

        /**
         * Returns a 0..1 progress used to slide the toast off the right edge of
         * the screen once its display time elapses (0 = at rest, 1 = fully
         * off-screen). Stays at 0 for the whole visible period, then ramps up
         * over {@link #ANIM_OUT_MS} with an ease-in curve for a smooth exit.
         */
        public float slideOutProgress(long now) {
            long age = now - createdAtMs;
            long outStart = durationMs;
            if (age < outStart) return 0f;
            float t = clamp01((age - outStart) / (float) ANIM_OUT_MS);
            // Ease-in quart so the toast accelerates smoothly as it leaves.
            return t * t * t * t;
        }

        /**
         * Returns a 0..1 slide progress used to animate the toast into place
         * (0 = fully off-screen, 1 = at rest).
         */
        public float slideProgress(long now) {
            long age = now - createdAtMs;
            if (age <= 0) return 0f;
            if (age >= ANIM_IN_MS) return 1f;
            float t = age / (float) ANIM_IN_MS;
            // Ease-out quint for a buttery, gliding settle into place.
            return 1f - (float) Math.pow(1f - t, 5);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }

    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    
    // Performance: cache the active list snapshot to avoid recomputing every frame.
    // Invalidated whenever the queue changes (push/clear) or once per second.
    private static volatile List<Entry> cachedActive = new ArrayList<>();
    private static volatile long cacheValidUntilMs = 0L;
    private static final long CACHE_DURATION_MS = 1000L;

    private ToastQueue() {
    }

    /** Queues a toast with the default duration. Ignores blank messages. */
    public static void push(String message) {
        push(message, DEFAULT_DURATION_MS);
    }

    /** Queues a toast with an explicit duration in milliseconds. */
    public static void push(String message, long durationMs) {
        if (message == null) return;
        String trimmed = message.strip();
        if (trimmed.isEmpty()) return;
        long now = System.currentTimeMillis();
        ENTRIES.add(new Entry(trimmed, now, Math.max(1000L, durationMs)));
        // Keep only the most recent MAX_VISIBLE entries so a burst of messages
        // does not stack endlessly down the screen.
        pruneExpired(now);
        while (ENTRIES.size() > MAX_VISIBLE) {
            ENTRIES.remove(0);
        }
        invalidateCache();
    }

    /**
     * Returns the currently live entries (newest last), removing any that have
     * fully expired. Safe to call every frame from the render thread.
     * 
     * Performance: returns a cached snapshot that's refreshed at most once per
     * second to avoid expensive pruning + ArrayList copy on every frame.
     */
    public static List<Entry> active() {
        long now = System.currentTimeMillis();
        
        // Fast path: return cached snapshot if still valid.
        if (now < cacheValidUntilMs && !cachedActive.isEmpty()) {
            return cachedActive;
        }
        
        // Slow path: rebuild cache.
        pruneExpired(now);
        cachedActive = ENTRIES.isEmpty() ? new ArrayList<>() : new ArrayList<>(ENTRIES);
        cacheValidUntilMs = now + CACHE_DURATION_MS;
        return cachedActive;
    }

    /** Clears every queued toast (used on shutdown / world switch if desired). */
    public static void clear() {
        ENTRIES.clear();
        invalidateCache();
    }

    private static void invalidateCache() {
        cacheValidUntilMs = 0L;
    }

    private static void pruneExpired(long now) {
        for (Entry e : ENTRIES) {
            if (e.isExpired(now)) {
                ENTRIES.remove(e);
            }
        }
    }
}
