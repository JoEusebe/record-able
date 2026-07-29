package dev.recordable.compat;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.lang.reflect.Method;

/**
 * Cross-version rendering helper for {@code DrawContext} text drawing.
 *
 * <h3>Why reflection (matched by parameter TYPE, not name)</h3>
 * <p>The legacy build targets 1.20.2-1.20.4. A previous
 * version called {@code DrawContext.drawTextWithShadow(...)} directly, which
 * Fabric Loom remaps to the intermediary method {@code method_25303}. In some
 * runtime setups (mapping drift, or mods such as ImmediatelyFast that rewrite
 * the text-render path) that specific method reference failed to resolve and
 * threw {@link NoSuchMethodError}, crashing the whole settings screen.</p>
 *
 * <p>To be bulletproof we resolve the draw method reflectively by matching its
 * <b>parameter types</b> (not its name). Parameter types resolve identically in
 * dev (named) and production (intermediary) environments, so this survives
 * remapping. We prefer the stable "shadow flag" overload
 * {@code (TextRenderer, String|Text, int, int, int, boolean)} which every
 * supported version delegates to, and fall back to the 5-arg shadowed overload.
 * Every call is wrapped in a try/catch so a text-render failure degrades to a
 * no-op instead of taking down the entire screen.</p>
 */
public final class RenderHelper {

    private RenderHelper() {}

    private static volatile boolean stringResolved;
    private static Method stringMethod;
    private static boolean stringHasShadowArg;

    private static volatile boolean textResolved;
    private static Method textMethod;
    private static boolean textHasShadowArg;

    private static void resolveString() {
        Method sixArg = null;   // (TextRenderer, String, int, int, int, boolean)
        Method fiveArg = null;  // (TextRenderer, String, int, int, int)
        for (Method m : DrawContext.class.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 6 && p[0] == TextRenderer.class && p[1] == String.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == boolean.class) {
                sixArg = m;
            } else if (p.length == 5 && p[0] == TextRenderer.class && p[1] == String.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                // Prefer the intermediary-named shadow variant if we can tell them apart.
                if (fiveArg == null || m.getName().contains("25303") || m.getName().toLowerCase().contains("shadow")) {
                    fiveArg = m;
                }
            }
        }
        if (sixArg != null) {
            stringMethod = sixArg;
            stringHasShadowArg = true;
        } else {
            stringMethod = fiveArg;
            stringHasShadowArg = false;
        }
        stringResolved = true;
    }

    private static void resolveText() {
        Method sixArg = null;   // (TextRenderer, Text, int, int, int, boolean)
        Method fiveArg = null;  // (TextRenderer, Text, int, int, int)
        for (Method m : DrawContext.class.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 6 && p[0] == TextRenderer.class && p[1] == Text.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == boolean.class) {
                sixArg = m;
            } else if (p.length == 5 && p[0] == TextRenderer.class && p[1] == Text.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                if (fiveArg == null || m.getName().contains("27535") || m.getName().toLowerCase().contains("shadow")) {
                    fiveArg = m;
                }
            }
        }
        if (sixArg != null) {
            textMethod = sixArg;
            textHasShadowArg = true;
        } else {
            textMethod = fiveArg;
            textHasShadowArg = false;
        }
        textResolved = true;
    }

    /** Renders a {@code String} with shadow. Never throws - degrades to a no-op on failure. */
    public static void drawText(DrawContext context, TextRenderer textRenderer,
                                String text, int x, int y, int color) {
        if (!stringResolved) {
            resolveString();
        }
        try {
            Method m = stringMethod;
            if (m == null) {
                return;
            }
            if (stringHasShadowArg) {
                m.invoke(context, textRenderer, text, x, y, color, true);
            } else {
                m.invoke(context, textRenderer, text, x, y, color);
            }
        } catch (Throwable ignored) {
            // Text rendering must never crash the screen.
        }
    }

    /** Renders a {@link Text} with shadow. Never throws - degrades to a no-op on failure. */
    public static void drawText(DrawContext context, TextRenderer textRenderer,
                                Text text, int x, int y, int color) {
        if (!textResolved) {
            resolveText();
        }
        try {
            Method m = textMethod;
            if (m == null) {
                return;
            }
            if (textHasShadowArg) {
                m.invoke(context, textRenderer, text, x, y, color, true);
            } else {
                m.invoke(context, textRenderer, text, x, y, color);
            }
        } catch (Throwable ignored) {
            // Text rendering must never crash the screen.
        }
    }

    /**
     * Draws horizontally-centered, shadowed {@code String} text at {@code centerX}.
     * Centering is computed manually via {@code TextRenderer.getWidth} and drawn
     * through the hardened {@link #drawText}, so no version-specific centered
     * method is ever referenced. Never throws.
     */
    public static void drawCenteredText(DrawContext context, TextRenderer textRenderer,
                                        String text, int centerX, int y, int color) {
        try {
            int w = textRenderer.getWidth(text == null ? "" : text);
            drawText(context, textRenderer, text, centerX - w / 2, y, color);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draws horizontally-centered, shadowed {@link Text} at {@code centerX}.
     * See {@link #drawCenteredText(DrawContext, TextRenderer, String, int, int, int)}.
     * Never throws.
     */
    public static void drawCenteredText(DrawContext context, TextRenderer textRenderer,
                                        Text text, int centerX, int y, int color) {
        try {
            int w = textRenderer.getWidth(text == null ? "" : text.getString());
            drawText(context, textRenderer, text, centerX - w / 2, y, color);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draws word-wrapped, shadowed text without relying on any version-specific
     * {@code DrawContext} wrapped-text method. Wraps manually using
     * {@code TextRenderer.getWidth} (stable across every supported version) and
     * draws each line through the hardened {@link #drawText} above. Never throws.
     *
     * @return the Y coordinate just below the last drawn line.
     */
    public static int drawWrappedText(DrawContext context, TextRenderer textRenderer,
                                      Text text, int x, int y, int wrapWidth, int color) {
        try {
            String raw = text == null ? "" : text.getString();
            int lineHeight = textRenderer.fontHeight + 1;
            int lineY = y;
            for (String line : wrapPlainText(textRenderer, raw, wrapWidth)) {
                drawText(context, textRenderer, line, x, lineY, color);
                lineY += lineHeight;
            }
            return lineY;
        } catch (Throwable ignored) {
            return y;
        }
    }

    /**
     * Greedy word-wrap of a plain string to a pixel width using
     * {@code TextRenderer.getWidth}. Falls back to hard character splitting for
     * single words longer than {@code wrapWidth}. Handles explicit newlines.
     */
    private static java.util.List<String> wrapPlainText(TextRenderer textRenderer, String raw, int wrapWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty() || wrapWidth <= 0) {
            lines.add(raw == null ? "" : raw);
            return lines;
        }
        for (String paragraph : raw.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (textRenderer.getWidth(candidate) <= wrapWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                // Word itself may exceed the width - hard-split it.
                if (textRenderer.getWidth(word) <= wrapWidth) {
                    current.append(word);
                } else {
                    StringBuilder chunk = new StringBuilder();
                    for (int i = 0; i < word.length(); i++) {
                        char c = word.charAt(i);
                        if (textRenderer.getWidth(chunk.toString() + c) > wrapWidth && chunk.length() > 0) {
                            lines.add(chunk.toString());
                            chunk.setLength(0);
                        }
                        chunk.append(c);
                    }
                    if (chunk.length() > 0) {
                        current.append(chunk);
                    }
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    /**
     * Returns whether we're running on MC 1.20.5 or newer.
     * Always {@code false} for this legacy build (targets 1.20.2-1.20.4).
     */
    public static boolean is1_20_5Plus() {
        return false;
    }
}
