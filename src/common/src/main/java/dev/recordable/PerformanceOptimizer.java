package dev.recordable;

import java.util.ArrayList;
import java.util.List;

/**
 * V1-0.06 Feature 7: Performance Optimizer.
 *
 * <p>Pure-Java decision engine (shared across all variants) that watches live
 * {@link PerformanceMetrics} and, when enabled, recommends or applies optimizations
 * to keep the game's framerate above the configured target. The actual application
 * of a recommendation (changing the live encoder resolution/fps/preset) is performed
 * per-variant; this class decides <em>what</em> should change.</p>
 *
 * <p>It is intentionally conservative: it only reacts after sustained low FPS,
 * applies one step at a time, and includes a cooldown so it never thrashes.</p>
 */
public final class PerformanceOptimizer {

    /** Kinds of optimization the engine can recommend, in order of escalation. */
    public enum Action {
        NONE,
        FASTER_PRESET,
        LOWER_FPS,
        LOWER_RESOLUTION
    }

    /** A recommendation produced by {@link #evaluate}. */
    public record Recommendation(Action action, String reason, boolean needsConfirmation) {
        public static final Recommendation NONE = new Recommendation(Action.NONE, "", false);
    }

    private static final PerformanceOptimizer INSTANCE = new PerformanceOptimizer();
    public static PerformanceOptimizer getInstance() { return INSTANCE; }

    /** Number of consecutive low-FPS samples required before acting. */
    private static final int LOW_FPS_SAMPLES = 3;
    /** Cooldown between applied optimizations (milliseconds). */
    private static final long COOLDOWN_MS = 8000L;

    private int lowFpsStreak = 0;
    private long lastActionAtMs = 0L;
    private final List<Action> appliedThisSession = new ArrayList<>();

    private PerformanceOptimizer() {}

    /** Reset state at the start of a recording. */
    public synchronized void reset() {
        lowFpsStreak = 0;
        lastActionAtMs = 0L;
        appliedThisSession.clear();
    }

    public synchronized List<Action> getAppliedActions() {
        return List.copyOf(appliedThisSession);
    }

    /**
     * Evaluate the current frame rate against the target and decide whether an
     * optimization should be applied. Should be called periodically (e.g. once a
     * second) while recording.
     *
     * @param currentFps the game's current FPS (from the client)
     * @param config     the active configuration
     * @return a {@link Recommendation}; {@link Recommendation#NONE} when no action is needed
     */
    public synchronized Recommendation evaluate(int currentFps, RecordableConfig config) {
        if (config == null || !config.perfOptimizerEnabled) {
            return Recommendation.NONE;
        }

        int target = Math.max(10, config.perfMinFps);
        if (currentFps >= target) {
            // Recovered - relax the streak counter.
            lowFpsStreak = Math.max(0, lowFpsStreak - 1);
            return Recommendation.NONE;
        }

        lowFpsStreak++;
        if (lowFpsStreak < LOW_FPS_SAMPLES) {
            return Recommendation.NONE;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionAtMs < COOLDOWN_MS) {
            return Recommendation.NONE;
        }

        Action next = nextEnabledAction(config);
        if (next == Action.NONE) {
            return Recommendation.NONE;
        }

        String reason = "FPS " + currentFps + " below target " + target;
        boolean confirm = config.perfWarnBeforeAdjust;

        if (config.perfAutoAdjust && !confirm) {
            // Auto-apply: advance state immediately.
            markApplied(next, now);
        }
        return new Recommendation(next, reason, confirm || !config.perfAutoAdjust);
    }

    /** Caller invokes this once a recommended action has actually been applied. */
    public synchronized void markApplied(Action action, long whenMs) {
        if (action == null || action == Action.NONE) return;
        appliedThisSession.add(action);
        lastActionAtMs = whenMs;
        lowFpsStreak = 0;
    }

    /** Pick the next escalation step that is enabled in config and not yet applied. */
    private Action nextEnabledAction(RecordableConfig config) {
        // Escalation order respects the "game priority" preference. When the user
        // wants game framerate protected above all (perfModeGamePriority), reach for
        // the highest-impact action (lower resolution) first since it recovers the
        // most performance fastest. Otherwise favor recording quality: try the
        // cheapest visual impact first (faster preset) and only fall back to
        // lowering resolution as a last resort.
        Action[] order = config.perfModeGamePriority
                ? new Action[]{Action.LOWER_RESOLUTION, Action.LOWER_FPS, Action.FASTER_PRESET}
                : new Action[]{Action.FASTER_PRESET, Action.LOWER_FPS, Action.LOWER_RESOLUTION};
        for (Action a : order) {
            if (appliedThisSession.contains(a)) continue;
            if (isEnabled(a, config)) return a;
        }
        return Action.NONE;
    }

    private boolean isEnabled(Action a, RecordableConfig config) {
        return switch (a) {
            case FASTER_PRESET -> config.perfActionFasterPreset;
            case LOWER_FPS -> config.perfActionLowerFps;
            case LOWER_RESOLUTION -> config.perfActionLowerRes;
            case NONE -> false;
        };
    }

    /** Human-readable label for an action (used by UI + logs). */
    public static String describe(Action a) {
        return switch (a) {
            case FASTER_PRESET -> "Switched to a faster encoder preset";
            case LOWER_FPS -> "Lowered recording frame rate";
            case LOWER_RESOLUTION -> "Lowered recording resolution";
            case NONE -> "No change";
        };
    }
}
