package dev.recordable;

import net.minecraft.client.gui.DrawContext;

import java.lang.reflect.Method;

/**
 * Version-safe HUD matrix-stack access for the sandwich build (MC 1.20.5-1.21.11).
 *
 * <p>Minecraft 1.21.6 refactored {@code DrawContext.getMatrices()} from the old
 * 4x4 {@code MatrixStack} (net.minecraft.class_4587) to a 2D
 * {@code org.joml.Matrix3x2fStack}. The sandwich jar is compiled against 1.21.11,
 * so calling {@code getMatrices()} directly on 1.20.5-1.21.5 throws
 * {@link NoSuchMethodError} because the return type in the compiled bytecode
 * (Matrix3x2fStack) does not match the runtime method (MatrixStack). That is the
 * crash reported on 1.21.4.</p>
 *
 * <p>This helper resolves the stack object and its push / pop / scale / translate
 * operations entirely by reflection, trying the new (1.21.6+) method names first
 * and falling back to the old (pre-1.21.6) intermediary names. No compile-time
 * reference to Matrix3x2fStack is made, so the old JOML shipped with 1.20.5-1.21.5
 * (which lacks that class) never has to be loaded.</p>
 */
public final class HudMat {
    private HudMat() {}

    // DrawContext.getMatrices() -> the matrix stack object (type varies by version).
    private static Method getMatrices;
    private static boolean getMatricesResolved;

    // Operations on the returned stack object, resolved lazily once the class is known.
    private static Method mPush;
    private static Method mPop;
    private static Method mScale2;   // new: scale(float, float)
    private static Method mScale3;   // old: scale(float, float, float)
    private static Method mTranslate2; // new: translate(float, float)
    private static Method mTranslate3; // old: translate(double, double, double)
    private static Method mRotate;     // new: rotate(float radians); absent on old MatrixStack
    private static Class<?> opsClass;

    private static Object stack(DrawContext ctx) {
        if (!getMatricesResolved) {
            getMatricesResolved = true;
            // "method_51448" is the intermediary name used in production jars;
            // "getMatrices" is the yarn name used in a dev runtime.
            for (String name : new String[] { "method_51448", "getMatrices" }) {
                try {
                    getMatrices = ctx.getClass().getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try next candidate
                }
            }
        }
        if (getMatrices == null) return null;
        try {
            return getMatrices.invoke(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method find(Class<?> c, String[] names, Class<?>... params) {
        for (String n : names) {
            try {
                return c.getMethod(n, params);
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            }
        }
        return null;
    }

    private static void resolveOps(Class<?> c) {
        if (c == opsClass) return;
        opsClass = c;
        // push: new Matrix3x2fStack.pushMatrix(); old MatrixStack.push() = method_22903
        mPush = find(c, new String[] { "pushMatrix", "method_22903", "push" });
        // pop: new popMatrix(); old pop() = method_22909
        mPop = find(c, new String[] { "popMatrix", "method_22909", "pop" });
        // scale: new scale(float,float); old scale(float,float,float) = method_22905
        mScale2 = find(c, new String[] { "scale", "method_22905" }, float.class, float.class);
        mScale3 = find(c, new String[] { "method_22905", "scale" }, float.class, float.class, float.class);
        // translate: new translate(float,float); old translate(double,double,double) = method_22904
        mTranslate2 = find(c, new String[] { "translate", "method_22904" }, float.class, float.class);
        mTranslate3 = find(c, new String[] { "method_22904", "translate" }, double.class, double.class, double.class);
        // rotate: new Matrix3x2fStack.rotate(float radians). The old MatrixStack has no
        // float-radians rotate, so it stays null and rotation degrades to no-op.
        mRotate = find(c, new String[] { "rotate" }, float.class);
    }

    /** Pushes a new matrix onto the HUD stack. */
    public static void push(DrawContext ctx) {
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mPush != null) mPush.invoke(ms);
        } catch (Throwable ignored) {}
    }

    /** Pops the top matrix off the HUD stack. */
    public static void pop(DrawContext ctx) {
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mPop != null) mPop.invoke(ms);
        } catch (Throwable ignored) {}
    }

    /** Scales the current HUD matrix (2D). */
    public static void scale(DrawContext ctx, float x, float y) {
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mScale2 != null) mScale2.invoke(ms, x, y);
            else if (mScale3 != null) mScale3.invoke(ms, x, y, 1.0f);
        } catch (Throwable ignored) {}
    }

    /** Translates the current HUD matrix (2D). */
    public static void translate(DrawContext ctx, float x, float y) {
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mTranslate2 != null) mTranslate2.invoke(ms, x, y);
            else if (mTranslate3 != null) mTranslate3.invoke(ms, (double) x, (double) y, 0.0d);
        } catch (Throwable ignored) {}
    }

    /**
     * Rotates the current HUD matrix about the Z axis by the given angle (radians).
     * On pre-1.21.6 (old MatrixStack) there is no float-radians rotate, so this is a
     * no-op and the affected element simply renders unrotated instead of crashing.
     */
    public static void rotate(DrawContext ctx, float radians) {
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mRotate != null) mRotate.invoke(ms, radians);
        } catch (Throwable ignored) {}
    }

    // DrawContext.createNewRootLayer() (1.21.6+) / draw() (pre-1.21.6) resolution.
    private static Method raiseLayerMethod;
    private static Method flushMethod;
    private static boolean raiseResolved;
    // Tracks whether the last beginOverlayLayer() pushed a matrix that endOverlayLayer()
    // must pop. Only one share modal is ever open at a time, so a single flag is enough.
    private static boolean overlayMatrixPushed;

    private static void resolveRaise(DrawContext ctx) {
        if (raiseResolved) return;
        raiseResolved = true;
        for (String name : new String[] { "createNewRootLayer", "method_74537" }) {
            try { raiseLayerMethod = ctx.getClass().getMethod(name); break; }
            catch (NoSuchMethodException ignored) {}
        }
        for (String name : new String[] { "draw", "method_51452" }) {
            try { flushMethod = ctx.getClass().getMethod(name); break; }
            catch (NoSuchMethodException ignored) {}
        }
    }

    /**
     * Raises subsequent drawing so a modal overlay composites ABOVE the batched text of
     * whatever was drawn earlier (list filenames, buttons). Must be paired with
     * {@link #endOverlayLayer(DrawContext)}.
     *
     * <p>On MC 1.21.6+ the retained-mode GUI exposes
     * {@code DrawContext.createNewRootLayer()} (intermediary {@code method_74537}),
     * which starts a new root layer drawn on top of all prior layers; that layer needs
     * no cleanup. On MC 1.20.5-1.21.5 the immediate-mode GUI has no layers and a single
     * {@code draw()} flush is not enough (the text layer still sorts above the gui-fill
     * layer inside one flush), so we flush the pending buffers, then push a z=400 matrix
     * (the same depth vanilla tooltips use) so the modal depth-tests in front of the
     * list text. That matrix is popped in {@link #endOverlayLayer(DrawContext)}.
     * Everything is resolved reflectively so the sandwich jar (compiled against 1.21.11)
     * does not hard-link a method absent at runtime.</p>
     */
    public static void beginOverlayLayer(DrawContext ctx) {
        resolveRaise(ctx);
        overlayMatrixPushed = false;
        try {
            if (raiseLayerMethod != null) { raiseLayerMethod.invoke(ctx); return; }
        } catch (Throwable ignored) {}
        // Old immediate-mode system: flush already-queued text, then lift by z=400.
        try { if (flushMethod != null) flushMethod.invoke(ctx); } catch (Throwable ignored) {}
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try {
            if (mPush != null && mTranslate3 != null) {
                mPush.invoke(ms);
                mTranslate3.invoke(ms, 0.0d, 0.0d, 400.0d);
                overlayMatrixPushed = true;
            }
        } catch (Throwable ignored) {}
    }

    /** Undoes {@link #beginOverlayLayer(DrawContext)} (pops the z=400 matrix if one was pushed). */
    public static void endOverlayLayer(DrawContext ctx) {
        if (!overlayMatrixPushed) return; // createNewRootLayer path needs no cleanup
        overlayMatrixPushed = false;
        Object ms = stack(ctx);
        if (ms == null) return;
        resolveOps(ms.getClass());
        try { if (mPop != null) mPop.invoke(ms); } catch (Throwable ignored) {}
    }
}
