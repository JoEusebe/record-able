package dev.recordable;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture-pipeline health engine (shared across all variants).
 *
 * <p>This is the "screen size aware detector" plus a full diagnostic report
 * builder. It turns a set of plain numeric inputs (window framebuffer size,
 * game render-target size, GUI scale, live black-frame stats, and an optional
 * one-shot self-test result) into a list of human-readable {@link Check}s with
 * an OK / WARN / FAIL status each, and an overall verdict.</p>
 *
 * <p>It intentionally references <b>no</b> Minecraft types so it can live in the
 * common module and be unit-tested in isolation. Variant code gathers the
 * numbers (via correctly-mapped client calls) and feeds them in. The only
 * Minecraft contact is {@link #readRenderTargetSize(Object)}, which is pure
 * reflection over an opaque {@code Object} and never imports a game class.</p>
 */
public final class CaptureDiagnostics {

    private CaptureDiagnostics() {
    }

    /** Severity of a single diagnostic line. */
    public enum Status {
        OK, WARN, FAIL, INFO
    }

    /** One diagnostic line: a status, a short label, and a detail string. */
    public record Check(Status status, String label, String detail) {
    }

    /** Snapshot of an active {@code ScreenCapture}'s live statistics. */
    public record LiveStats(
            long framesProduced,
            long blackFrames,
            int consecutiveBlack,
            boolean persistentBlack,
            String sourceName,
            int sourceWidth,
            int sourceHeight,
            int renderTargetWidth,
            int renderTargetHeight,
            boolean sizeMismatch) {
    }

    /** Result of a one-shot, on-demand capture self-test (run on the render thread). */
    public record SelfTestResult(
            boolean producedFrame,
            boolean black,
            double avgBrightness,
            int width,
            int height,
            String note) {
    }

    /**
     * All inputs needed to build a report. {@code renderTargetWidth/Height} are
     * {@code -1} when the game render-target size could not be read.
     * {@code liveStats} is {@code null} when nothing is recording, and
     * {@code selfTest} is {@code null} when the self-test has not finished yet.
     */
    public record Inputs(
            int windowWidth,
            int windowHeight,
            int renderTargetWidth,
            int renderTargetHeight,
            int guiScale,
            boolean recordingActive,
            LiveStats liveStats,
            SelfTestResult selfTest) {
    }

    /**
     * Builds the full ordered list of diagnostic checks for the given inputs.
     */
    public static List<Check> buildReport(Inputs in) {
        List<Check> checks = new ArrayList<>();

        // --- Screen-size aware detector -----------------------------------
        checks.add(new Check(Status.INFO, "Window framebuffer",
                in.windowWidth() + " x " + in.windowHeight()));

        boolean rtKnown = in.renderTargetWidth() > 0 && in.renderTargetHeight() > 0;
        if (rtKnown) {
            checks.add(new Check(Status.INFO, "Game render target",
                    in.renderTargetWidth() + " x " + in.renderTargetHeight()));
            boolean mismatch = in.renderTargetWidth() != in.windowWidth()
                    || in.renderTargetHeight() != in.windowHeight();
            if (mismatch) {
                checks.add(new Check(Status.FAIL, "Size match",
                        "Window (" + in.windowWidth() + "x" + in.windowHeight()
                                + ") and render target (" + in.renderTargetWidth() + "x"
                                + in.renderTargetHeight() + ") differ. Capture may read the wrong "
                                + "region or appear cropped/blank. This usually means the window was "
                                + "resized mid-capture or a shader mod (Iris/OptiFine) is drawing into "
                                + "a different-sized buffer."));
            } else {
                checks.add(new Check(Status.OK, "Size match",
                        "Window and render target sizes agree."));
            }
        } else {
            checks.add(new Check(Status.INFO, "Game render target",
                    "size unavailable on this version (window size used instead)."));
        }

        checks.add(new Check(Status.INFO, "GUI scale",
                in.guiScale() > 0 ? String.valueOf(in.guiScale()) : "unknown"));

        // --- Live capture statistics --------------------------------------
        checks.add(new Check(Status.INFO, "Recording active",
                in.recordingActive() ? "yes" : "no"));

        LiveStats live = in.liveStats();
        if (live != null) {
            checks.add(new Check(Status.INFO, "Frames produced",
                    String.valueOf(live.framesProduced())));
            checks.add(new Check(Status.INFO, "Capture source", live.sourceName()));

            if (live.persistentBlack()) {
                checks.add(new Check(Status.FAIL, "Black frames",
                        "Recording is persistently black after trying every capture source. "
                                + live.blackFrames() + " black frame(s) so far. Your GPU driver may "
                                + "block glReadPixels, or a shader mod is intercepting the framebuffer."));
            } else if (live.consecutiveBlack() > 0 || live.blackFrames() > 0) {
                checks.add(new Check(Status.WARN, "Black frames",
                        live.blackFrames() + " black frame(s) seen (" + live.consecutiveBlack()
                                + " in a row right now). Auto-recovery is cycling the capture source."));
            } else {
                checks.add(new Check(Status.OK, "Black frames", "none detected."));
            }

            if (live.sizeMismatch()) {
                checks.add(new Check(Status.WARN, "Live size check",
                        "The window size changed away from the recording size (" + live.sourceWidth()
                                + "x" + live.sourceHeight() + "). Frames are being rescaled to fit."));
            }
        }

        // --- One-shot self-test -------------------------------------------
        SelfTestResult st = in.selfTest();
        if (st == null) {
            checks.add(new Check(Status.INFO, "Self-test",
                    "running... (open this screen from in-game so a frame can be drawn)."));
        } else if (!st.producedFrame()) {
            checks.add(new Check(Status.FAIL, "Self-test",
                    "Could not read a frame from the GPU. " + (st.note() == null ? "" : st.note())
                            + " glReadPixels returned nothing - capture cannot work in this state."));
        } else if (st.black()) {
            checks.add(new Check(Status.FAIL, "Self-test",
                    "The captured test frame is black (avg brightness " + fmt(st.avgBrightness())
                            + "). The pipeline reads an empty framebuffer, so the GUI/world is not "
                            + "reaching the captured surface."));
        } else {
            checks.add(new Check(Status.OK, "Self-test",
                    "Captured a normal frame at " + st.width() + "x" + st.height()
                            + " (avg brightness " + fmt(st.avgBrightness()) + ")."));
        }

        return checks;
    }

    /** Reduces a list of checks to a single overall verdict. */
    public static Status overallVerdict(List<Check> checks) {
        Status worst = Status.OK;
        for (Check c : checks) {
            if (c.status() == Status.FAIL) {
                return Status.FAIL;
            }
            if (c.status() == Status.WARN) {
                worst = Status.WARN;
            }
        }
        return worst;
    }

    /** One-line human summary for the given verdict. */
    public static String verdictSummary(Status verdict) {
        return switch (verdict) {
            case FAIL -> "Capture problem detected - see the failed checks below.";
            case WARN -> "Capture works but something needs attention.";
            default -> "Capture looks healthy.";
        };
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * Best-effort reflection read of a render target's pixel size. Accepts the
     * opaque framebuffer / render-target object obtained by variant code and
     * tries common getter methods and public field names across Yarn and
     * Mojmap mappings. Returns {@code {width, height}} or {@code null} when the
     * size cannot be determined (callers must treat that as "unknown", never as
     * an error).
     */
    public static int[] readRenderTargetSize(Object target) {
        if (target == null) {
            return null;
        }
        int w = readIntMember(target, "getWidth", "width", "textureWidth", "viewportWidth");
        int h = readIntMember(target, "getHeight", "height", "textureHeight", "viewportHeight");
        if (w > 0 && h > 0) {
            return new int[]{w, h};
        }
        return null;
    }

    private static int readIntMember(Object obj, String... names) {
        Class<?> cls = obj.getClass();
        for (String name : names) {
            try {
                java.lang.reflect.Method m = cls.getMethod(name);
                Object v = m.invoke(obj);
                if (v instanceof Integer i && i > 0) {
                    return i;
                }
            } catch (Throwable ignored) {
                // not a method; try a field next
            }
            try {
                java.lang.reflect.Field f = cls.getField(name);
                Object v = f.get(obj);
                if (v instanceof Integer i && i > 0) {
                    return i;
                }
            } catch (Throwable ignored) {
                // try next candidate name
            }
        }
        return -1;
    }
}
