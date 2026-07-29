package dev.recordable;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Reads the final OpenGL backbuffer into raw RGB frames for FFmpeg.
 *
 * <p>Two pixel-buffer objects are used in a ping-pong pattern. Each render tick
 * submits the current {@code glReadPixels} asynchronously and maps the previous
 * PBO, keeping GPU/CPU synchronization pressure low. The first call after start
 * or resize returns {@code null} because the readback is intentionally one frame
 * behind.</p>
 */
public final class ScreenCapture implements AutoCloseable {
    private static final int BYTES_PER_PIXEL = 3;

    private final int outputWidth;
    private final int outputHeight;
    private final int outputByteSize;
    private final int[] pboIds = new int[2];

    private int sourceWidth;
    private int sourceHeight;
    private int sourceByteSize;
    private int pboWriteIndex;
    private boolean hasPendingPboFrame;
    // Android now uses the async PBO path too. Earlier builds disabled PBOs on Android
    // because the desktop-only glMapBuffer() is a silent no-op on the GLES translation
    // layers used by Pojav/Zalith/MobileGlues (it returns null without throwing). The
    // real fix is to map the readback with glMapBufferRange() + GL_MAP_READ_BIT, which
    // is core in OpenGL ES 3.0 (see mapPackBufferForRead()). The async ping-pong lets
    // the render thread continue immediately after issuing glReadPixels instead of
    // stalling on the synchronous glFinish + glGetTexImage readback, which was the cause
    // of the ~12-13 unique-FPS / 58%-duplicate-frame problem on Android. If the PBO path
    // ever yields null maps, an allocation error, or persistent black frames, we fall
    // back permanently to the known-good synchronous texture readback for the session.
    private boolean pboSupported = true;
    // True on Android/GLES layers; selects the ES-compatible map call and the
    // texture-readback fallback used by disablePboAndFallBackToSync().
    private final boolean android = PlatformUtils.isAndroid();
    private final boolean vulkanRenderer = PlatformUtils.isVulkanRendererLoaded() && !android;
    private Robot desktopCaptureRobot;
    private boolean desktopCaptureFailureLogged;
    private int[] desktopArgbScratch;
    // Defensive runtime guard: even on platforms where PBOs were enabled at init,
    // glMapBuffer() can start returning null. After this many consecutive null
    // maps we permanently switch to synchronous readback for the session.
    private static final int MAX_PBO_MAP_FAILURES = 5;
    private int consecutivePboMapFailures = 0;
    private ByteBuffer fallbackReadBuffer;
    // Reusable heap copy of the GL readback buffer. The per-pixel flip/scale loop
    // reads this plain array instead of the direct ByteBuffer: array access is
    // JIT-friendly (no per-element bounds/JNI cost) and a single bulk copy is far
    // cheaper than millions of ByteBuffer.get(int) calls on the render thread.
    private byte[] rawConvertScratch;

    // --- Off-thread frame conversion pipeline (desktop async PBO path) ------
    // The GPU->CPU readback (glReadPixels into a PBO, then glMapBuffer) must run
    // on the render thread because it needs the GL context. The per-pixel flip +
    // scale + channel swizzle and the black-frame scan, however, are pure CPU
    // work over millions of pixels and touch no GL state. Running them on the
    // render thread for every captured frame was the main cause of the in-game
    // FPS drop players reported: the recording itself stayed complete (the frames
    // were still produced correctly) but the render thread paid the conversion
    // cost, so the live game stuttered while the captured video looked fine.
    //
    // We now hand the raw mapped bytes to a single background worker that does
    // the conversion + validation, so the render thread only pays for the
    // unavoidable GL calls plus one native memcpy of the readback. A single
    // worker thread plus a FIFO queue keeps frame order intact.
    private static final int MAX_READY_FRAMES = 4;
    private ExecutorService conversionExecutor;
    private final LinkedBlockingQueue<PendingFrame> readyFrames = new LinkedBlockingQueue<>();
    private final ArrayDeque<byte[]> rawBufferPool = new ArrayDeque<>();
    private final Object rawBufferPoolLock = new Object();

    // --- OpenGL ES (Android) readback format -------------------------------
    // On GLES translation layers (MobileGlues/ANGLE, GL4ES), glReadPixels does NOT
    // accept GL_BGR: ES only guarantees GL_RGBA + GL_UNSIGNED_BYTE. Requesting
    // GL_BGR raises GL_INVALID_ENUM and writes nothing, causing all-black frames.
    // Native OpenGL providers on Android (Zink over Vulkan, VirGL/software) do
    // support GL_BGR and use the faster 3-channel path like desktop.
    // The renderer is detected in the constructor from GL_RENDERER + LIBGL_EGL env.
    private final PlatformUtils.AndroidRenderer androidRenderer;
    private final boolean glesRgbaReadback;
    private final int glReadFormat;
    private final int sourceChannels;
    private boolean readbackDiagnosticsLogged = false;

    // --- Black-frame detection & auto-recovery -----------------------------
    // Some GPU/driver/mod combinations leave the "wrong" framebuffer bound at
    // our capture point, so glReadPixels reads an empty (black) surface. We
    // detect that and cycle through alternate read sources to self-heal.
    //
    //   AUTO         (0): read whatever draw FBO is currently bound (default)
    //   MAIN_FBO     (1): read Minecraft's main render framebuffer explicitly
    //   BACK_BUFFER  (2): read the window back buffer (fb0 / GL_BACK)
    //   MAIN_TEXTURE (3): read the main framebuffer's COLOR-ATTACHMENT TEXTURE via
    //                     glGetTexImage instead of glReadPixels. This mirrors how
    //                     vanilla Minecraft takes F2 screenshots, which is the ONLY
    //                     readback path proven to work on Pojav/Zalith GLES layers
    //                     (where glReadPixels silently returns all-black with no GL
    //                     error). It is the default first source on Android.
    private static final int READ_SOURCE_AUTO = 0;
    private static final int READ_SOURCE_MAIN_FBO = 1;
    private static final int READ_SOURCE_BACK_BUFFER = 2;
    private static final int READ_SOURCE_MAIN_TEXTURE = 3;
    private static final int READ_SOURCE_COUNT = 4;
    // Switch capture source after this many consecutive black frames (~0.25s @ 60fps).
    private static final int BLACK_RECOVERY_THRESHOLD = 15;

    // The async PBO path reads the currently-bound draw FBO (via glReadPixels into the
    // PBO) on every platform, so we start in AUTO. On Android, if PBOs are later disabled
    // (map failure/allocation error/persistent black frames), disablePboAndFallBackToSync()
    // pins this to MAIN_TEXTURE - the glGetTexImage readback proven to work on GLES layers.
    private int readSourceMode = READ_SOURCE_AUTO;
    private boolean textureReadbackDiagnosticsLogged = false;
    private long totalFramesProduced;
    private long totalBlackFrames;
    private int consecutiveBlackFrames;
    private int blackRecoverySwitches;

    // --- Screen-size aware detector ----------------------------------------
    // Compares the window framebuffer size against Minecraft's main render
    // target size every capture. A persistent mismatch is a strong signal that
    // frames will be cropped, misaligned, or blank (e.g. window resized
    // mid-capture, or a shader mod drawing into a different-sized buffer).
    private int lastWindowWidth;
    private int lastWindowHeight;
    private int renderTargetWidth = -1;
    private int renderTargetHeight = -1;
    private boolean sizeMismatchLogged;
    private boolean vulkanNoDesktopCaptureLogged;

    public ScreenCapture(int outputWidth, int outputHeight) {
        this.outputWidth = Math.max(2, outputWidth);
        this.outputHeight = Math.max(2, outputHeight);
        this.outputByteSize = this.outputWidth * this.outputHeight * BYTES_PER_PIXEL;
        if (vulkanRenderer) {
            if (GraphicsEnvironment.isHeadless()) {
                RecordableMod.LOGGER.warn("Vulkan renderer detected, desktop Robot fallback unavailable in headless environment.");
            } else {
            try {
                desktopCaptureRobot = new Robot();
                desktopCaptureRobot.setAutoWaitForIdle(false);
                RecordableMod.LOGGER.info("Vulkan renderer detected, using desktop window capture fallback.");
            } catch (AWTException | SecurityException e) {
                RecordableMod.LOGGER.warn("Vulkan fallback capture could not initialize desktop Robot.", e);
            }
            }
        }
        // Detect the Android renderer to pick the correct readback format.
        // GL11.glGetString is safe here: ScreenCapture is always constructed from
        // the render thread with an active GL context.
        if (android) {
            String glRendStr = null;
            try { glRendStr = GL11.glGetString(GL11.GL_RENDERER); } catch (Throwable ignored) {}
            androidRenderer = PlatformUtils.detectAndroidRenderer(glRendStr);
            glesRgbaReadback = androidRenderer.requiresRgbaReadback();
            RecordableMod.LOGGER.info(
                    "Android platform detected: renderer={} (GL_RENDERER={}), readback={}, using async PBO "
                    + "screen capture. Falls back to sync texture readback if PBOs fail or frames stay black.",
                    androidRenderer, glRendStr, glesRgbaReadback ? "GL_RGBA" : "GL_BGR");
        } else {
            androidRenderer = PlatformUtils.AndroidRenderer.UNKNOWN;
            glesRgbaReadback = false;
        }
        glReadFormat = glesRgbaReadback ? GL11.GL_RGBA : GL12.GL_BGR;
        sourceChannels = glesRgbaReadback ? 4 : 3;
    }

    /**
     * Captures a frame from the default framebuffer/backbuffer.
     *
     * @return RGB top-down pixels at the configured output size, or {@code null}
     * when an async PBO readback is still warming up, or when the GL context
     * is not in a valid state (e.g., at the main menu before world load).
     */
    public synchronized CapturedFrame captureFrame() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return null;
        }

        int nativeWidth = client.getWindow().getFramebufferWidth();
        int nativeHeight = client.getWindow().getFramebufferHeight();
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return null;
        }
        recordSizeInfo(nativeWidth, nativeHeight);
        if (vulkanRenderer) {
            if (desktopCaptureRobot == null) {
                if (!vulkanNoDesktopCaptureLogged) {
                    RecordableMod.LOGGER.warn("Vulkan capture unavailable, desktop Robot fallback is not initialized, skipping frame capture.");
                    vulkanNoDesktopCaptureLogged = true;
                }
                return null;
            }
            CapturedFrame desktopFrame = captureViaDesktopWindow(client);
            if (desktopFrame != null) {
                return desktopFrame;
            }
        }

        try {
            GL11.glGetError(); // clears any stale error
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("GL context not ready for screen capture; skipping frame.", throwable);
            return null;
        }

        try {
            if (!pboSupported) {
                return captureSynchronously(nativeWidth, nativeHeight);
            }

            // Take the oldest frame the background worker has finished converting.
            // Black-frame bookkeeping (and any GL-touching source recovery) runs
            // here on the render thread inside pollConvertedFrame().
            CapturedFrame readyFrame = pollConvertedFrame();

            // Map the previous PBO and hand its raw bytes to the conversion worker.
            // glMapBuffer() can return null WITHOUT throwing on some GLES translation
            // layers (the exact Android/MobileGlues failure). The catch block below
            // never sees that, so without this guard PBOs would silently yield zero
            // frames for the whole session. Count consecutive null maps (ignoring the
            // expected one-frame warmup where hasPendingPboFrame is false) and force
            // the synchronous path once they accumulate.
            if (hasPendingPboFrame) {
                boolean mapped = enqueuePendingPboConversion();
                if (!mapped) {
                    consecutivePboMapFailures++;
                    if (consecutivePboMapFailures >= MAX_PBO_MAP_FAILURES) {
                        disablePboAndFallBackToSync("PBO map returned null " + consecutivePboMapFailures
                                + " times consecutively");
                        return readyFrame != null ? readyFrame : captureSynchronously(nativeWidth, nativeHeight);
                    }
                } else {
                    consecutivePboMapFailures = 0;
                }
            }

            if (pboIds[0] == 0 || nativeWidth != sourceWidth || nativeHeight != sourceHeight) {
                initializePbos(nativeWidth, nativeHeight);
            }

            submitAsyncRead();
            return readyFrame;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("PBO screen capture failed; falling back to synchronous readback.", throwable);
            disablePboAndFallBackToSync("PBO capture threw " + throwable.getClass().getSimpleName());
            try {
                return captureSynchronously(nativeWidth, nativeHeight);
            } catch (Throwable syncThrowable) {
                RecordableMod.LOGGER.warn("Synchronous screen capture also failed; returning null.", syncThrowable);
                return null;
            }
        }
    }

    /**
     * Captures a frame <strong>synchronously</strong> from the currently-bound draw FBO,
     * bypassing the async PBO path entirely. Used by the filter pipeline, which needs a
     * deterministically-paired clean (pre-HUD) frame and full (post-HUD) frame from the
     * same render pass - async PBO frames lag by one frame and cannot be paired reliably.
     *
     * @return RGB top-down pixels at the configured output size, or {@code null} when the
     * GL context/window is not ready.
     */
    public synchronized CapturedFrame captureFrameSynchronous() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return null;
        }
        int nativeWidth = client.getWindow().getFramebufferWidth();
        int nativeHeight = client.getWindow().getFramebufferHeight();
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return null;
        }
        recordSizeInfo(nativeWidth, nativeHeight);
        if (vulkanRenderer) {
            CapturedFrame desktopFrame = captureViaDesktopWindow(client);
            if (desktopFrame != null) {
                return desktopFrame;
            }
        }
        try {
            GL11.glGetError();
        } catch (Throwable throwable) {
            return null;
        }
        return captureSynchronously(nativeWidth, nativeHeight);
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    /**
     * Blocks until the GPU has finished executing all previously-submitted commands,
     * guaranteeing the current frame is fully rendered into the framebuffer/texture
     * before we read it back. Only applied on Android/GLES translation layers, where
     * command execution is asynchronous and an unsynced readback grabs the previous
     * frame (causing duplicate/stale frames and a frozen startup stretch in the
     * recording). No-op on desktop, which uses the async PBO path and does not need it.
     */
    private void syncGpuBeforeReadback() {
        if (!PlatformUtils.isAndroid()) {
            return;
        }
        try {
            GL11.glFinish();
        } catch (Throwable ignored) {
            // Best effort: if glFinish is unavailable on this layer we simply proceed;
            // worst case is the pre-existing duplicate-frame behaviour, never a crash.
        }
    }

    private CapturedFrame captureSynchronously(int nativeWidth, int nativeHeight) {
        // Texture-based readback (vanilla screenshot path). Primary source on Android,
        // where glReadPixels returns all-black on the GLES translation layer.
        if (readSourceMode == READ_SOURCE_MAIN_TEXTURE) {
            CapturedFrame textureFrame = captureViaColorTexture(nativeWidth, nativeHeight);
            if (textureFrame != null) {
                return textureFrame;
            }
            // Texture unavailable: fall through to glReadPixels for this frame.
        }
        try {
            sourceWidth = nativeWidth;
            sourceHeight = nativeHeight;
            sourceByteSize = checkedByteSize(sourceWidth, sourceHeight, sourceChannels);
            ensureFallbackReadBuffer();

            int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            try {
                // Read from the currently-bound draw FBO (Minecraft's main framebuffer)
                // rather than hardcoded fb0 (screen backbuffer). This is critical when
                // capturing before InGameHud.render() - at that point, the main FBO has
                // the current frame's world rendering, while fb0 still has the previous
                // frame's blitted output (including HUD and filters).
                int activeFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int readFbo = resolveReadFbo(activeFbo);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
                GL11.glReadBuffer(readFbo == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                fallbackReadBuffer.clear();
                // Force the current frame to finish rendering before the synchronous
                // readback so we never capture the previous (stale) frame. See the
                // detailed note in captureViaColorTexture().
                syncGpuBeforeReadback();
                GL11.glReadPixels(0, 0, sourceWidth, sourceHeight, glReadFormat, GL11.GL_UNSIGNED_BYTE, fallbackReadBuffer);
                logReadbackDiagnosticsOnce();
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            }

            byte[] rgb = FrameBufferPool.getInstance().acquire(outputByteSize);
            copyFlipScaleToRgb(fallbackReadBuffer, sourceWidth, sourceHeight, rgb, outputWidth, outputHeight,
                    sourceChannels, glesRgbaReadback);
            evaluateFrameForBlackScreen(rgb);
            return new CapturedFrame(rgb, outputWidth, outputHeight, System.nanoTime());
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Synchronous screen capture failed.", throwable);
            return null;
        }
    }

    private void initializePbos(int newSourceWidth, int newSourceHeight) {
        deletePbosSafely();
        sourceWidth = newSourceWidth;
        sourceHeight = newSourceHeight;
        sourceByteSize = checkedByteSize(sourceWidth, sourceHeight, sourceChannels);
        pboWriteIndex = 0;
        hasPendingPboFrame = false;

        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int index = 0; index < pboIds.length; index++) {
                pboIds[index] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[index]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, sourceByteSize, GL15.GL_STREAM_READ);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
        }
    }

    private void submitAsyncRead() {
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);

        try {
            // Read from the currently-bound draw FBO (Minecraft's main framebuffer).
            // See captureSynchronously() for detailed explanation.
            int activeFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFbo = resolveReadFbo(activeFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glReadBuffer(readFbo == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[pboWriteIndex]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, sourceByteSize, GL15.GL_STREAM_READ);
            GL11.glReadPixels(0, 0, sourceWidth, sourceHeight, glReadFormat, GL11.GL_UNSIGNED_BYTE, 0L);
            logReadbackDiagnosticsOnce();

            pboWriteIndex = (pboWriteIndex + 1) % pboIds.length;
            hasPendingPboFrame = true;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
    }

    /**
     * Maps the currently-bound {@code GL_PIXEL_PACK_BUFFER} for reading.
     *
     * <p>On Android/GLES translation layers we must map with
     * {@code glMapBufferRange(GL_MAP_READ_BIT)}, which is core in OpenGL ES 3.0. The
     * desktop-only {@code glMapBuffer()} is absent on those layers and silently returns
     * null, which is why earlier Android PBO attempts produced zero frames. Desktop keeps
     * the plain {@code glMapBuffer()} path.</p>
     *
     * @return the mapped buffer, or {@code null} when the driver/layer cannot map it.
     */
    private ByteBuffer mapPackBufferForRead(int length) {
        if (android) {
            return GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, length, GL30.GL_MAP_READ_BIT);
        }
        return GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, length, null);
    }

    /**
     * Permanently disables the async PBO readback for the rest of this session and
     * switches to the synchronous fallback. On Android the synchronous path is pinned to
     * the texture readback ({@code glGetTexImage}), the only path proven to work on GLES
     * translation layers; on desktop it uses synchronous {@code glReadPixels}.
     */
    private void disablePboAndFallBackToSync(String reason) {
        if (!pboSupported) {
            return;
        }
        RecordableMod.LOGGER.warn(
                "Disabling async PBO readback ({}); using synchronous {} readback for the rest of this session.",
                reason, android ? "texture (glGetTexImage)" : "glReadPixels");
        pboSupported = false;
        if (android) {
            readSourceMode = READ_SOURCE_MAIN_TEXTURE;
            readbackDiagnosticsLogged = false;
            textureReadbackDiagnosticsLogged = false;
        }
        consecutivePboMapFailures = 0;
        deletePbosSafely();
        drainReadyFrames();
    }

    /**
     * Maps the previously-submitted PBO, copies its raw readback bytes into a
     * pooled heap buffer with a single native memcpy, unmaps it, and hands the
     * raw bytes to the background conversion worker. The expensive per-pixel
     * flip/scale/swizzle and the black-frame scan then run OFF the render thread.
     *
     * <p>Only the GL calls (map/unmap/bind) and the one memcpy stay on the render
     * thread here, which is what keeps the in-game FPS cost low.</p>
     *
     * @return {@code true} when a frame was mapped and enqueued, {@code false}
     * when {@code glMapBuffer} returned null (the GLES no-op failure).
     */
    private boolean enqueuePendingPboConversion() {
        int readIndex = (pboWriteIndex + 1) % pboIds.length;
        int previousPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        // Snapshot the conversion parameters on the render thread. The worker must
        // never read mutable capture state (it can change between frames).
        final int sw = sourceWidth;
        final int sh = sourceHeight;
        final int channels = sourceChannels;
        final boolean rgba = glesRgbaReadback;
        final int srcTotal = sw * channels * sh;
        ByteBuffer mapped = null;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[readIndex]);
            mapped = mapPackBufferForRead(sourceByteSize);
            if (mapped == null) {
                return false;
            }

            byte[] raw = acquireRawBuffer(srcTotal);
            int savedPos = mapped.position();
            int savedLimit = mapped.limit();
            mapped.position(0);
            int readable = Math.min(srcTotal, mapped.remaining());
            mapped.get(raw, 0, readable);
            mapped.position(savedPos);
            mapped.limit(savedLimit);

            submitConversion(raw, sw, sh, channels, rgba, System.nanoTime());
            return true;
        } finally {
            if (mapped != null) {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPbo);
        }
    }

    /**
     * Returns the oldest frame the conversion worker has finished, or {@code null}
     * when none are ready yet (the intentional pipeline warmup). Runs on the render
     * thread and performs the black-frame bookkeeping/recovery using the flag the
     * worker precomputed, so any GL-touching recovery stays on the render thread.
     */
    private CapturedFrame pollConvertedFrame() {
        PendingFrame pending = readyFrames.poll();
        if (pending == null) {
            return null;
        }
        recordBlackFrameResult(pending.black());
        return new CapturedFrame(pending.rgb(), outputWidth, outputHeight, pending.capturedAtNanos());
    }

    /** Submits a raw readback buffer to the single-threaded conversion worker. */
    private void submitConversion(byte[] raw, int sw, int sh, int channels, boolean rgba, long capturedAt) {
        conversionExecutor().execute(() -> {
            try {
                byte[] rgb = FrameBufferPool.getInstance().acquire(outputByteSize);
                convertRawToRgb(raw, sw, sh, rgb, outputWidth, outputHeight, channels, rgba);
                boolean black = FrameValidator.isBlackFrame(rgb, outputWidth, outputHeight);
                // Bound the queue: if the consumer ever falls behind, drop the
                // oldest converted frame (returning its buffer to the pool) so
                // memory use stays bounded.
                while (readyFrames.size() >= MAX_READY_FRAMES) {
                    PendingFrame dropped = readyFrames.poll();
                    if (dropped != null) {
                        FrameBufferPool.getInstance().release(dropped.rgb());
                    }
                }
                readyFrames.offer(new PendingFrame(rgb, black, capturedAt));
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("Async frame conversion failed.", t);
            } finally {
                releaseRawBuffer(raw);
            }
        });
    }

    private ExecutorService conversionExecutor() {
        ExecutorService exec = conversionExecutor;
        if (exec == null) {
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "Record-able-FrameConvert");
                thread.setDaemon(true);
                // Slightly below normal so the worker never competes with the
                // render/main threads for a core on constrained devices.
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            };
            exec = Executors.newSingleThreadExecutor(factory);
            conversionExecutor = exec;
        }
        return exec;
    }

    private byte[] acquireRawBuffer(int size) {
        synchronized (rawBufferPoolLock) {
            byte[] buffer = rawBufferPool.pollFirst();
            if (buffer != null && buffer.length >= size) {
                return buffer;
            }
        }
        return new byte[size];
    }

    private void releaseRawBuffer(byte[] buffer) {
        if (buffer == null) {
            return;
        }
        synchronized (rawBufferPoolLock) {
            if (rawBufferPool.size() < MAX_READY_FRAMES + 1) {
                rawBufferPool.offerFirst(buffer);
            }
        }
    }

    /** Drops any queued converted frames, returning their buffers to the pool. */
    private void drainReadyFrames() {
        PendingFrame pending;
        while ((pending = readyFrames.poll()) != null) {
            FrameBufferPool.getInstance().release(pending.rgb());
        }
    }

    private void ensureFallbackReadBuffer() {
        if (fallbackReadBuffer != null && fallbackReadBuffer.capacity() >= sourceByteSize) {
            return;
        }
        if (fallbackReadBuffer != null) {
            MemoryUtil.memFree(fallbackReadBuffer);
        }
        fallbackReadBuffer = MemoryUtil.memAlloc(sourceByteSize);
    }

    private static int checkedByteSize(int width, int height, int channels) {
        long bytes = width * (long) height * channels;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Capture buffer is too large: " + width + "x" + height);
        }
        return (int) bytes;
    }

    private void copyFlipScaleToRgb(ByteBuffer sourceBottomUp, int sourceWidth, int sourceHeight,
                                    byte[] targetRgbTopDown, int targetWidth, int targetHeight,
                                    int srcChannels, boolean sourceIsRgba) {
        final int srcRowStride = sourceWidth * srcChannels;
        final int srcTotal = srcRowStride * sourceHeight;

        // Bulk-copy the (direct) GL readback buffer into a reusable heap array in a
        // single native memcpy, then index the plain array in the hot loop below.
        // Reading a direct ByteBuffer via absolute get(int) inside a multi-million
        // iteration per-pixel loop is dramatically slower than plain array access
        // (each get() is bounds-checked and not inlined), and this loop runs on the
        // render thread every captured frame - so it was a primary cause of the FPS
        // drops reported on Android, whose native framebuffers are large and use the
        // 4-channel RGBA readback path.
        byte[] src = rawConvertScratch;
        if (src == null || src.length < srcTotal) {
            src = new byte[srcTotal];
            rawConvertScratch = src;
        }
        int savedPos = sourceBottomUp.position();
        int savedLimit = sourceBottomUp.limit();
        sourceBottomUp.position(0);
        int readable = Math.min(srcTotal, sourceBottomUp.remaining());
        sourceBottomUp.get(src, 0, readable);
        sourceBottomUp.position(savedPos);
        sourceBottomUp.limit(savedLimit);

        convertRawToRgb(src, sourceWidth, sourceHeight, targetRgbTopDown, targetWidth, targetHeight,
                srcChannels, sourceIsRgba);
    }

    /**
     * Pure-CPU flip + fixed-point scale + channel swizzle from a raw bottom-up
     * readback byte array into a top-down RGB byte array. Contains no GL calls and
     * reads no mutable capture state, so it is safe to run on the background
     * conversion worker as well as on the render thread (the synchronous paths).
     */
    private static void convertRawToRgb(byte[] src, int sourceWidth, int sourceHeight,
                                        byte[] targetRgbTopDown, int targetWidth, int targetHeight,
                                        int srcChannels, boolean sourceIsRgba) {
        final int srcRowStride = sourceWidth * srcChannels;

        // Fixed-point integer scaling (multiply then shift right 16).
        final int FP_SHIFT = 16;
        final int scaleX = (sourceWidth << FP_SHIFT) / targetWidth;
        final int scaleY = (sourceHeight << FP_SHIFT) / targetHeight;

        // Hoist the channel-order decision out of the inner loop.
        //   RGBA (GLES/Android): source bytes are R,G,B,A -> offsets 0,1,2
        //   BGR  (desktop):      source bytes are B,G,R   -> offsets 2,1,0
        final int rOff = sourceIsRgba ? 0 : 2;
        final int gOff = 1;
        final int bOff = sourceIsRgba ? 2 : 0;

        for (int targetY = 0; targetY < targetHeight; targetY++) {
            int sourceYTopDown = Math.min(sourceHeight - 1, (targetY * scaleY) >> FP_SHIFT);
            int sourceYBottomUp = sourceHeight - 1 - sourceYTopDown;
            int srcRowBase = sourceYBottomUp * srcRowStride;
            int tgtRowBase = targetY * targetWidth * BYTES_PER_PIXEL;

            for (int targetX = 0; targetX < targetWidth; targetX++) {
                int sourceX = Math.min(sourceWidth - 1, (targetX * scaleX) >> FP_SHIFT);
                int sourceIndex = srcRowBase + sourceX * srcChannels;
                int targetIndex = tgtRowBase + targetX * BYTES_PER_PIXEL;

                targetRgbTopDown[targetIndex] = src[sourceIndex + rOff];
                targetRgbTopDown[targetIndex + 1] = src[sourceIndex + gOff];
                targetRgbTopDown[targetIndex + 2] = src[sourceIndex + bOff];
            }
        }
    }

    private CapturedFrame captureViaDesktopWindow(MinecraftClient client) {
        if (desktopCaptureRobot == null || client == null || client.getWindow() == null) {
            return null;
        }
        long handle = resolveWindowHandle(client.getWindow());
        if (handle == 0L) {
            if (!desktopCaptureFailureLogged) {
                desktopCaptureFailureLogged = true;
                RecordableMod.LOGGER.warn("Vulkan fallback capture could not resolve the GLFW window handle.");
            }
            return null;
        }
        IntBuffer xBuf = MemoryUtil.memAllocInt(1);
        IntBuffer yBuf = MemoryUtil.memAllocInt(1);
        IntBuffer wBuf = MemoryUtil.memAllocInt(1);
        IntBuffer hBuf = MemoryUtil.memAllocInt(1);
        try {
            GLFW.glfwGetWindowPos(handle, xBuf, yBuf);
            GLFW.glfwGetWindowSize(handle, wBuf, hBuf);
            int capX = xBuf.get(0);
            int capY = yBuf.get(0);
            int capW = wBuf.get(0);
            int capH = hBuf.get(0);
            if (capW <= 1 || capH <= 1) {
                return null;
            }
            BufferedImage screenshot = desktopCaptureRobot.createScreenCapture(new Rectangle(capX, capY, capW, capH));
            byte[] rgb = FrameBufferPool.getInstance().acquire(outputByteSize);
            copyBufferedImageToRgb(screenshot, rgb, outputWidth, outputHeight);
            evaluateFrameForBlackScreen(rgb);
            return new CapturedFrame(rgb, outputWidth, outputHeight, System.nanoTime());
        } catch (RuntimeException e) {
            if (!desktopCaptureFailureLogged) {
                desktopCaptureFailureLogged = true;
                RecordableMod.LOGGER.warn("Vulkan fallback desktop capture failed.", e);
            }
            return null;
        } finally {
            MemoryUtil.memFree(xBuf);
            MemoryUtil.memFree(yBuf);
            MemoryUtil.memFree(wBuf);
            MemoryUtil.memFree(hBuf);
        }
    }

    private static long resolveWindowHandle(Object window) {
        if (window == null) {
            return 0L;
        }
        String[] methodNames = new String[]{"getWindow", "getHandle", "getGlfwHandle", "getWindowHandle"};
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method method = window.getClass().getMethod(methodName);
                Object value = method.invoke(window);
                if (value instanceof Long handle && handle.longValue() != 0L) {
                    return handle;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return 0L;
    }

    private void copyBufferedImageToRgb(BufferedImage image, byte[] targetRgbTopDown, int targetWidth, int targetHeight) {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int totalPixels = sourceWidth * sourceHeight;
        if (desktopArgbScratch == null || desktopArgbScratch.length < totalPixels) {
            desktopArgbScratch = new int[totalPixels];
        }
        image.getRGB(0, 0, sourceWidth, sourceHeight, desktopArgbScratch, 0, sourceWidth);
        for (int y = 0; y < targetHeight; y++) {
            int sy = y * sourceHeight / targetHeight;
            int srcRow = sy * sourceWidth;
            int dstRow = y * targetWidth * BYTES_PER_PIXEL;
            for (int x = 0; x < targetWidth; x++) {
                int sx = x * sourceWidth / targetWidth;
                int argb = desktopArgbScratch[srcRow + sx];
                int dstIndex = dstRow + x * BYTES_PER_PIXEL;
                targetRgbTopDown[dstIndex] = (byte) ((argb >>> 16) & 0xFF);
                targetRgbTopDown[dstIndex + 1] = (byte) ((argb >>> 8) & 0xFF);
                targetRgbTopDown[dstIndex + 2] = (byte) (argb & 0xFF);
            }
        }
    }

    /**
     * Logs the chosen glReadPixels format and any GL error once per capture session.
     * On OpenGL ES, a non-zero error here (typically 0x500 GL_INVALID_ENUM) means the
     * requested pixel format is unsupported and frames will be black.
     */
    private void logReadbackDiagnosticsOnce() {
        if (readbackDiagnosticsLogged) {
            return;
        }
        readbackDiagnosticsLogged = true;
        int err;
        try {
            err = GL11.glGetError();
        } catch (Throwable t) {
            return;
        }
        RecordableMod.LOGGER.info(
                "Screen capture readback: format={} channels={} ({} path), source {}x{}, glReadPixels error=0x{}",
                glesRgbaReadback ? "GL_RGBA" : "GL_BGR", sourceChannels,
                glesRgbaReadback ? "OpenGL ES/Android" : "desktop GL",
                sourceWidth, sourceHeight, Integer.toHexString(err));
        if (err != 0) {
            RecordableMod.LOGGER.warn(
                    "glReadPixels reported GL error 0x{} on first capture - captured frames may be black. "
                            + "On OpenGL ES only GL_RGBA + GL_UNSIGNED_BYTE is guaranteed for readback.",
                    Integer.toHexString(err));
        }
    }

    private void deletePbosSafely() {
        // GL calls require an OpenGL context, which only exists on the render thread.
        // When called from a JVM shutdown hook (e.g. crash or force-quit), there is no
        // GL context and LWJGL will abort the JVM with a fatal native error.
        // In that case we simply skip the cleanup - the GPU resources are freed when
        // the process exits anyway.
        if (!hasGLContext()) {
            RecordableMod.LOGGER.debug("Skipping PBO cleanup - no OpenGL context on current thread ({})", Thread.currentThread().getName());
            hasPendingPboFrame = false;
            return;
        }
        try {
            for (int index = 0; index < pboIds.length; index++) {
                if (pboIds[index] != 0) {
                    GL15.glDeleteBuffers(pboIds[index]);
                    pboIds[index] = 0;
                }
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Failed to delete screen-capture PBOs.", throwable);
        }
        hasPendingPboFrame = false;
    }

    /**
     * Resolves the OpenGL framebuffer object id to read from, based on the
     * current {@link #readSourceMode}. Falls back to the active draw FBO when a
     * mode cannot be satisfied (e.g. main framebuffer not available yet).
     *
     * @param activeDrawFbo the currently-bound GL_DRAW_FRAMEBUFFER id
     */
    private int resolveReadFbo(int activeDrawFbo) {
        switch (readSourceMode) {
            case READ_SOURCE_MAIN_FBO -> {
                int mainFbo = mainFramebufferId();
                return mainFbo >= 0 ? mainFbo : activeDrawFbo;
            }
            case READ_SOURCE_BACK_BUFFER -> {
                return 0; // window back buffer
            }
            default -> {
                return activeDrawFbo;
            }
        }
    }

    /**
     * Returns the GL id of Minecraft's main render framebuffer, or {@code -1}
     * when it cannot be determined. Uses reflection so the same source compiles
     * across Yarn/Mojmap mappings and tolerates field renames between versions.
     */
    private static int mainFramebufferId() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return -1;
            }
            Object framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                return -1;
            }
            for (String fieldName : new String[]{"fbo", "field_1476", "frameBufferId"}) {
                try {
                    java.lang.reflect.Field f = framebuffer.getClass().getField(fieldName);
                    Object v = f.get(framebuffer);
                    if (v instanceof Integer i && i > 0) {
                        return i;
                    }
                } catch (NoSuchFieldException ignored) {
                    // try next candidate name
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Captures a frame by downloading Minecraft's main framebuffer COLOR-ATTACHMENT
     * TEXTURE via {@code glGetTexImage}, instead of reading a framebuffer with
     * {@code glReadPixels}. This is exactly how vanilla Minecraft saves F2 screenshots
     * ({@code ScreenshotRecorder} -> {@code NativeImage.loadFromTextureImage}), and it
     * is the ONLY readback path that returns real pixels on Pojav/Zalith/MobileGlues
     * GLES translation layers, where {@code glReadPixels} silently yields all-black.
     *
     * @return a captured frame, or {@code null} when the color texture cannot be
     * resolved (caller then falls back to the glReadPixels path).
     */
    private CapturedFrame captureViaColorTexture(int nativeWidth, int nativeHeight) {
        int textureId = mainColorAttachmentTexture();
        if (textureId <= 0) {
            return null;
        }
        int texWidth = nativeWidth;
        int texHeight = nativeHeight;
        int[] texSize = mainFramebufferTextureSize();
        if (texSize != null && texSize[0] > 0 && texSize[1] > 0) {
            texWidth = texSize[0];
            texHeight = texSize[1];
        }

        sourceWidth = texWidth;
        sourceHeight = texHeight;
        sourceByteSize = checkedByteSize(sourceWidth, sourceHeight, sourceChannels);
        ensureFallbackReadBuffer();

        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            fallbackReadBuffer.clear();
            // Android/GLES translation layers (MobileGlues, Pojav, ANGLE) execute the
            // current frame's draw commands asynchronously. Without an explicit GPU
            // sync, glGetTexImage below can return the PREVIOUS frame's contents while
            // this frame is still being rendered - producing stale, duplicate frames
            // in the recording (visible as ~half-rate judder and a frozen stretch at
            // the very start of a session). glFinish blocks until the current frame has
            // actually been rendered into the texture, so every capture is unique.
            // Desktop uses the async PBO path and never reaches this code.
            syncGpuBeforeReadback();
            // glGetTexImage returns the texture in its native (bottom-up) orientation,
            // identical to glReadPixels, so the existing vertical-flip in
            // copyFlipScaleToRgb applies unchanged.
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, glReadFormat, GL11.GL_UNSIGNED_BYTE, fallbackReadBuffer);
            logTextureReadbackDiagnosticsOnce();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Texture-based screen capture failed; falling back to glReadPixels.", throwable);
            return null;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }

        byte[] rgb = FrameBufferPool.getInstance().acquire(outputByteSize);
        copyFlipScaleToRgb(fallbackReadBuffer, sourceWidth, sourceHeight, rgb, outputWidth, outputHeight,
                sourceChannels, glesRgbaReadback);
        evaluateFrameForBlackScreen(rgb);
        return new CapturedFrame(rgb, outputWidth, outputHeight, System.nanoTime());
    }

    /**
     * Returns the GL texture id of Minecraft's main framebuffer color attachment, or
     * {@code -1} when it cannot be determined. Prefers the public accessor method
     * ({@code getColorAttachment()} / Yarn {@code method_30277}) and falls back to the
     * backing field across mappings.
     */
    private static int mainColorAttachmentTexture() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return -1;
            }
            Object framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                return -1;
            }
            for (String methodName : new String[]{"getColorAttachment", "method_30277"}) {
                try {
                    java.lang.reflect.Method m = framebuffer.getClass().getMethod(methodName);
                    Object v = m.invoke(framebuffer);
                    if (v instanceof Integer i && i > 0) {
                        return i;
                    }
                } catch (NoSuchMethodException ignored) {
                    // try next candidate
                }
            }
            for (String fieldName : new String[]{"colorAttachment", "field_1476", "field_1480"}) {
                try {
                    java.lang.reflect.Field f = framebuffer.getClass().getField(fieldName);
                    Object v = f.get(framebuffer);
                    if (v instanceof Integer i && i > 0) {
                        return i;
                    }
                } catch (NoSuchFieldException ignored) {
                    // try next candidate
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Best-effort lookup of the main framebuffer's backing texture dimensions
     * ({@code textureWidth}/{@code textureHeight}). Returns {@code null} when the
     * fields cannot be resolved, in which case the caller uses the window size.
     */
    private static int[] mainFramebufferTextureSize() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            Object framebuffer = client.getFramebuffer();
            if (framebuffer == null) {
                return null;
            }
            Integer w = readIntField(framebuffer, new String[]{"textureWidth", "field_1480"});
            Integer h = readIntField(framebuffer, new String[]{"textureHeight", "field_1477"});
            if (w != null && h != null && w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer readIntField(Object target, String[] candidateNames) {
        for (String name : candidateNames) {
            try {
                java.lang.reflect.Field f = target.getClass().getField(name);
                Object v = f.get(target);
                if (v instanceof Integer i) {
                    return i;
                }
            } catch (NoSuchFieldException ignored) {
                // try next candidate
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Logs the texture-readback format and any GL error once per capture session. */
    private void logTextureReadbackDiagnosticsOnce() {
        if (textureReadbackDiagnosticsLogged) {
            return;
        }
        textureReadbackDiagnosticsLogged = true;
        int err;
        try {
            err = GL11.glGetError();
        } catch (Throwable t) {
            return;
        }
        RecordableMod.LOGGER.info(
                "Screen capture readback: TEXTURE path (vanilla-screenshot style glGetTexImage), "
                        + "format={} channels={}, source {}x{}, glGetTexImage error=0x{}",
                glesRgbaReadback ? "GL_RGBA" : "GL_BGR", sourceChannels,
                sourceWidth, sourceHeight, Integer.toHexString(err));
        if (err != 0) {
            RecordableMod.LOGGER.warn(
                    "glGetTexImage reported GL error 0x{} on first texture capture - frames may be black; "
                            + "auto-recovery will try glReadPixels sources next.",
                    Integer.toHexString(err));
        }
    }

    /**
     * Evaluates a freshly produced frame for the "black screen" failure mode and
     * advances the capture source if black frames persist. Called for every
     * frame the capture actually produces.
     */
    private void evaluateFrameForBlackScreen(byte[] rgb) {
        recordBlackFrameResult(FrameValidator.isBlackFrame(rgb, outputWidth, outputHeight));
    }

    /**
     * Applies black-frame bookkeeping and auto-recovery given an already-computed
     * black flag. Always runs on the render thread (from the synchronous paths
     * directly, or from {@link #pollConvertedFrame()} for the async worker path),
     * so the recovery branch may safely issue GL calls via deletePbosSafely().
     */
    private void recordBlackFrameResult(boolean black) {
        totalFramesProduced++;
        if (!black) {
            consecutiveBlackFrames = 0;
            return;
        }

        totalBlackFrames++;
        consecutiveBlackFrames++;
        // On Android, persistent black frames from the async PBO path mean the GLES
        // layer's glReadPixels is unreliable. Skip source-cycling and drop straight to
        // the proven synchronous texture readback (glGetTexImage) instead.
        if (consecutiveBlackFrames == BLACK_RECOVERY_THRESHOLD && android && pboSupported) {
            consecutiveBlackFrames = 0;
            disablePboAndFallBackToSync("persistent black frames from the async PBO readback");
            return;
        }
        if (consecutiveBlackFrames == BLACK_RECOVERY_THRESHOLD && blackRecoverySwitches < READ_SOURCE_COUNT) {
            int previousMode = readSourceMode;
            readSourceMode = (readSourceMode + 1) % READ_SOURCE_COUNT;
            blackRecoverySwitches++;
            consecutiveBlackFrames = 0;
            // Re-arm per-source diagnostics so the newly selected source logs its
            // own format/error line once.
            readbackDiagnosticsLogged = false;
            textureReadbackDiagnosticsLogged = false;
            RecordableMod.LOGGER.warn(
                    "Black-screen capture detected ({} consecutive black frames). Switching capture source {} -> {} "
                            + "(recovery attempt {}/{}). If recordings stay black, your GPU driver may block "
                            + "glReadPixels or a shader mod (Iris/OptiFine) is intercepting the framebuffer.",
                    BLACK_RECOVERY_THRESHOLD, readSourceName(previousMode), readSourceName(readSourceMode),
                    blackRecoverySwitches, READ_SOURCE_COUNT);
            // Force PBO re-init so the next async submit binds the new source.
            deletePbosSafely();
        }
    }

    private static String readSourceName(int mode) {
        return switch (mode) {
            case READ_SOURCE_MAIN_FBO -> "MAIN_FBO";
            case READ_SOURCE_BACK_BUFFER -> "BACK_BUFFER";
            case READ_SOURCE_MAIN_TEXTURE -> "MAIN_TEXTURE";
            default -> "AUTO";
        };
    }

    /** Number of frames produced so far this session. */
    public synchronized long getTotalFramesProduced() {
        return totalFramesProduced;
    }

    /** Number of frames that were classified as (near) fully black. */
    public synchronized long getTotalBlackFrames() {
        return totalBlackFrames;
    }

    /** Consecutive black frames at the tail of the stream (0 once a good frame arrives). */
    public synchronized int getConsecutiveBlackFrames() {
        return consecutiveBlackFrames;
    }

    /** Human-readable name of the active capture source (AUTO / MAIN_FBO / BACK_BUFFER). */
    public synchronized String getReadSourceName() {
        return readSourceName(readSourceMode);
    }

    /**
     * Returns {@code true} if the capture appears to be persistently black even
     * after exhausting all auto-recovery source switches.
     */
    public synchronized boolean isPersistentlyBlack() {
        return blackRecoverySwitches >= READ_SOURCE_COUNT && consecutiveBlackFrames >= BLACK_RECOVERY_THRESHOLD;
    }

    /**
     * Records the live window size and (best-effort) the game render-target size
     * for the screen-size aware detector, logging once when a persistent
     * mismatch is first seen.
     */
    private void recordSizeInfo(int windowWidth, int windowHeight) {
        lastWindowWidth = windowWidth;
        lastWindowHeight = windowHeight;
        int[] rt = currentRenderTargetSize();
        if (rt != null) {
            renderTargetWidth = rt[0];
            renderTargetHeight = rt[1];
            if (!sizeMismatchLogged
                    && (renderTargetWidth != windowWidth || renderTargetHeight != windowHeight)) {
                sizeMismatchLogged = true;
                RecordableMod.LOGGER.warn(
                        "Capture size mismatch: window framebuffer is {}x{} but the game render "
                                + "target is {}x{}. Frames may be cropped, misaligned, or blank. This "
                                + "often means the window was resized mid-capture or a shader mod is "
                                + "drawing into a different-sized buffer.",
                        windowWidth, windowHeight, renderTargetWidth, renderTargetHeight);
            }
        }
    }

    /**
     * Best-effort read of Minecraft's main render-target pixel size. Returns
     * {@code {width, height}} or {@code null} when it cannot be determined (for
     * example on a remapped runtime where field names are obfuscated). Callers
     * must treat {@code null} as "unknown", never as an error.
     */
    public static int[] currentRenderTargetSize() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            return CaptureDiagnostics.readRenderTargetSize(client.getFramebuffer());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Last window framebuffer width seen by the capture pipeline. */
    public synchronized int getSourceWidth() {
        return lastWindowWidth;
    }

    /** Last window framebuffer height seen by the capture pipeline. */
    public synchronized int getSourceHeight() {
        return lastWindowHeight;
    }

    /** Last known game render-target width, or {@code -1} when unknown. */
    public synchronized int getRenderTargetWidth() {
        return renderTargetWidth;
    }

    /** Last known game render-target height, or {@code -1} when unknown. */
    public synchronized int getRenderTargetHeight() {
        return renderTargetHeight;
    }

    /** Whether the known render-target size differs from the window framebuffer size. */
    public synchronized boolean hasSizeMismatch() {
        return renderTargetWidth > 0 && renderTargetHeight > 0
                && (renderTargetWidth != lastWindowWidth || renderTargetHeight != lastWindowHeight);
    }

    /**
     * Returns {@code true} if the current thread has a valid OpenGL context.
     */
    private static boolean hasGLContext() {
        try {
            org.lwjgl.opengl.GL.getCapabilities();
            return true;
        } catch (IllegalStateException ignored) {
            // LWJGL throws IllegalStateException when no context is current
            return false;
        }
    }

    @Override
    public synchronized void close() {
        ExecutorService exec = conversionExecutor;
        conversionExecutor = null;
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        drainReadyFrames();
        synchronized (rawBufferPoolLock) {
            rawBufferPool.clear();
        }
        deletePbosSafely();
        if (fallbackReadBuffer != null) {
            MemoryUtil.memFree(fallbackReadBuffer);
            fallbackReadBuffer = null;
        }
        rawConvertScratch = null;
    }

    public record CapturedFrame(byte[] rgbPixels, int width, int height, long capturedAtNanos) {
    }

    /**
     * A fully-converted RGB frame plus the black-frame flag the conversion worker
     * precomputed. Produced off the render thread and consumed on it, where the
     * black-frame bookkeeping/recovery is applied.
     */
    private record PendingFrame(byte[] rgb, boolean black, long capturedAtNanos) {
    }
}
