package dev.recordable;

import java.util.HashMap;
import java.util.Map;

/**
 * V1-0.08 Streamer Mode: a tiny built-in 5x7 bitmap font used to paint censor
 * labels directly into the recorded RGB24 pixel buffer.
 *
 * <p>Minecraft's own font renderer is not available at the point where censoring
 * runs (we operate on a raw top-down RGB24 buffer produced by {@code ScreenCapture}
 * on a background thread), so this self-contained font lets the same code path run
 * identically on every variant (legacy / sandwich / modern) and on Android.</p>
 *
 * <p>Each glyph is 5 pixels wide and 7 pixels tall, encoded as seven row bitmasks
 * (bit 4 = leftmost column ... bit 0 = rightmost column). Characters are uppercased
 * before lookup; anything without a glyph renders as a blank space.</p>
 */
public final class CensorFont {

    /** Glyph cell width in source pixels (before scaling). */
    public static final int GLYPH_W = 5;
    /** Glyph cell height in source pixels (before scaling). */
    public static final int GLYPH_H = 7;
    /** Horizontal gap between glyphs in source pixels (before scaling). */
    public static final int GLYPH_GAP = 1;

    private static final Map<Character, int[]> GLYPHS = new HashMap<>();

    private CensorFont() {}

    private static void put(char c, int r0, int r1, int r2, int r3, int r4, int r5, int r6) {
        GLYPHS.put(c, new int[]{r0, r1, r2, r3, r4, r5, r6});
    }

    static {
        put(' ', 0, 0, 0, 0, 0, 0, 0);
        put('A', 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('B', 0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110);
        put('C', 0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111);
        put('D', 0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110);
        put('E', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111);
        put('F', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000);
        put('G', 0b01111, 0b10000, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111);
        put('H', 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        put('I', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111);
        put('J', 0b00111, 0b00010, 0b00010, 0b00010, 0b10010, 0b10010, 0b01100);
        put('K', 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001);
        put('L', 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111);
        put('M', 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001);
        put('N', 0b10001, 0b11001, 0b10101, 0b10101, 0b10011, 0b10001, 0b10001);
        put('O', 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('P', 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000);
        put('Q', 0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101);
        put('R', 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001);
        put('S', 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110);
        put('T', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100);
        put('U', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        put('V', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100);
        put('W', 0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001);
        put('X', 0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001);
        put('Y', 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100);
        put('Z', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111);
        put('0', 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110);
        put('1', 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        put('2', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111);
        put('3', 0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110);
        put('4', 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010);
        put('5', 0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110);
        put('6', 0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110);
        put('7', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000);
        put('8', 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110);
        put('9', 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110);
        put('.', 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100);
        put(':', 0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000);
        put('-', 0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000);
        put('_', 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111);
        put('!', 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100);
        put('?', 0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0b00000, 0b00100);
        put('/', 0b00001, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b10000);
        put('#', 0b01010, 0b01010, 0b11111, 0b01010, 0b11111, 0b01010, 0b01010);
        put('+', 0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000);
        put(',', 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b00100, 0b01000);
    }

    /** Width in source pixels (scale 1) of the given text, including inter-glyph gaps. */
    public static int textWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = text.length();
        return n * GLYPH_W + (n - 1) * GLYPH_GAP;
    }

    /**
     * Paints {@code text} into a top-down RGB24 buffer, fully opaque, at the given
     * top-left pixel position and integer scale. Pixels outside the frame are skipped.
     *
     * @param rgb         destination buffer (3 bytes per pixel, row-major, top-down)
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @param startX      left pixel of the first glyph
     * @param startY      top pixel of the glyph row
     * @param text        text to paint (uppercased before lookup)
     * @param rgbColor    text colour as 0xRRGGBB
     * @param scale       integer pixel scale (>= 1)
     */
    public static void drawText(byte[] rgb, int frameWidth, int frameHeight,
                                int startX, int startY, String text, int rgbColor, int scale) {
        if (rgb == null || text == null || text.isEmpty() || scale < 1) {
            return;
        }
        String upper = text.toUpperCase();
        byte cr = (byte) ((rgbColor >> 16) & 0xFF);
        byte cg = (byte) ((rgbColor >> 8) & 0xFF);
        byte cb = (byte) (rgbColor & 0xFF);
        int rowStride = frameWidth * 3;

        int penX = startX;
        for (int ci = 0; ci < upper.length(); ci++) {
            int[] glyph = GLYPHS.get(upper.charAt(ci));
            if (glyph != null) {
                for (int gy = 0; gy < GLYPH_H; gy++) {
                    int bits = glyph[gy];
                    for (int gx = 0; gx < GLYPH_W; gx++) {
                        boolean on = ((bits >> (GLYPH_W - 1 - gx)) & 1) != 0;
                        if (!on) continue;
                        // Fill a scale x scale block for this lit source pixel.
                        int blockX = penX + gx * scale;
                        int blockY = startY + gy * scale;
                        for (int sy = 0; sy < scale; sy++) {
                            int fy = blockY + sy;
                            if (fy < 0 || fy >= frameHeight) continue;
                            int rowBase = fy * rowStride;
                            for (int sx = 0; sx < scale; sx++) {
                                int fx = blockX + sx;
                                if (fx < 0 || fx >= frameWidth) continue;
                                int idx = rowBase + fx * 3;
                                rgb[idx] = cr;
                                rgb[idx + 1] = cg;
                                rgb[idx + 2] = cb;
                            }
                        }
                    }
                }
            }
            penX += (GLYPH_W + GLYPH_GAP) * scale;
        }
    }
}
