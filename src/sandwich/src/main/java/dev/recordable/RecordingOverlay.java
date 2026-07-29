package dev.recordable;

import dev.recordable.compat.RenderHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * HUD overlay with multiple style presets rendered via Minecraft's rendering system.
 *
 * <p>Styles:</p>
 * <ul>
 *     <li><b>CLASSIC</b> (shown as "Speed-Runner's Classic") - original Record-able info panel (dark background, accent bar, stats)</li>
 *     <li><b>VHS</b> - retro tape look: brackets, PLAY ▶, ●REC, SP, battery, audio meters, tape counter, date.
 *         Each element is individually toggleable via the VHS detail toggles, so this single style covers the
 *         former minimal/classic/full presets.</li>
 *     <li><b>SYNTHWAVE</b> - neon-bordered panel with retro-futuristic ●REC + timer</li>
 *     <li><b>NONE</b> - nothing rendered (overlay hidden)</li>
 * </ul>
 *
 * <p>All VHS elements have individually configurable colors stored in {@link RecordableConfig}.</p>
 */
public final class RecordingOverlay {
    private static boolean registered;
    private static int blinkTick;
    /** Tape counter increments every ~second. */
    private static int tapeCounter;
    private static int lastTapeSecond = -1;
    /** Simulated audio level for audio meter (0.0-1.0). */
    private static float simulatedAudioLevel;
    private static final Random AUDIO_RNG = new Random();

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FORMAT_12H =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private static final Identifier OS_CURSOR_TEXTURE =
            VersionHelper.id(RecordableMod.MOD_ID, "textures/gui/os_cursor.png");
    private static final int OS_CURSOR_BASE_WIDTH = 32;
    private static final int OS_CURSOR_BASE_HEIGHT = 32;
    private static final float POINTER_VISUAL_SCALE_FACTOR = 0.8f;
    private static long cachedDateSecond = Long.MIN_VALUE;
    private static String cachedDateLine = "";
    private static String cachedTimeLine = "";
    private static int cachedTapeCounter = Integer.MIN_VALUE;
    private static String cachedTapeLine = "TC 0000";

    private RecordingOverlay() {
    }

    /**
     * Registers the HUD overlay renderer.
     *
     * <p>On MC 1.21.6+ this uses {@code HudElementRegistry.addLast()} so the
     * overlay (REC indicator, timestamp, watermarks) draws as a
     * proper HUD element in the normal HUD render pass. The deprecated
     * {@code HudRenderCallback} is kept only as a fallback for older Fabric API.</p>
     */
    @SuppressWarnings("deprecation")
    public static void register() {
        if (registered) return;
        registered = true;
        // Screen-level censor overlay: HUD draws do NOT render while a GUI screen
        // (e.g. inventory) is open, so we also draw the live censor over open
        // screens. Only active when "Bake in Overlay" is OFF (live overlay mode).
        try {
            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                    ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, tickDelta) -> {
                        renderCensorOnScreen(ctx, client);
                        // HudRenderCallback does not fire while a Screen is open, so render toast
                        // notifications here too. This keeps "recording saved" / transfer notices
                        // visible on the title, multiplayer and world-select menus.
                        if (client != null && client.textRenderer != null) {
                            RecordingManager manager = RecordingManager.getInstance();
                            renderToastNotification(ctx, client, manager);
                            renderMousePointer(ctx, client, RecordableConfig.get(), manager, mouseX, mouseY);
                        }
                    }));
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Screen censor overlay unavailable: {}", t.toString());
        }
        // Prefer the modern HudElementRegistry (Fabric API 16.x / MC 1.21.6+). The
        // deprecated HudRenderCallback still exists on 1.21.11 but does NOT reliably
        // render custom draws batched into the GuiRenderState during the @TAIL inject,
        // which is why watermarks failed to appear on this variant. HudElementRegistry
        // registers a proper HUD element that is drawn last, matching the Modern variant.
        try {
            HudElementRegistry.addLast(
                    VersionHelper.id(RecordableMod.MOD_ID, "overlay"),
                    RecordingOverlay::render
            );
            return;
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("HudElementRegistry unavailable, falling back to HudRenderCallback: {}", t.toString());
        }
        // Fallback for older Fabric API where HudElementRegistry is absent.
        try {
            HudRenderCallback.EVENT.register(RecordingOverlay::render);
        } catch (Throwable t2) {
            RecordableMod.LOGGER.error("Failed to register HUD overlay for this Minecraft version", t2);
        }
    }

    /**
     * Draws the live censor overlay over an open GUI screen (inventory, chest,
     * etc.). Mirrors the OFF-mode gating of {@link #renderCensorPreview}: only
     * when Streamer Mode is on, "Bake in Overlay" is OFF, and the overlay has not
     * been hidden via the "Toggle Censor Overlay" hotkey.
     */
    private static void renderCensorOnScreen(DrawContext context, MinecraftClient client) {
        RecordableConfig config = RecordableConfig.get();
        if (config == null || !config.streamerModeEnabled) return;
        // Only show censor overlay when actually in a world/server, not on main menu or loading screens
        if (client == null || client.world == null) return;
        if (config.bakeInOverlay) return;        // ON: baked only; no live screen overlay.
        if (config.censorOverlayHidden) return;  // hidden via hotkey.
        if (client.textRenderer == null) return;
        drawCensorRegions(context, client, config);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main render dispatcher
    // ═════════════════════════════════════════════════════════════════════════

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        RecordableConfig config = RecordableConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) return;

        RecordingManager manager = RecordingManager.getInstance();

        // Toast notification is always available
        renderToastNotification(context, client, manager);

        // Watermarks render regardless of overlay/recording gate below (live preview + baking).
        renderWatermarks(context, client, config, manager);

        // Mic / PTT indicator renders regardless of the overlay gate below so it stays
        // visible while recording even when the HUD overlay is hidden or set to NONE.
        renderMicIndicator(context, client);

        // V1-0.08 Streamer Mode: outline censor regions on screen while recording.
        renderCensorPreview(context, client, config, manager);

        // Optional pointer overlay for tutorial-style recordings.
        renderMousePointer(context, client, config, manager, -1, -1);

        // Performance: skip expensive overlay rendering when not recording or overlay hidden.
        if (!config.showOverlay || !manager.isActiveOrStopping()) return;

        // Live-preview video filters render as a full-screen underlay beneath the HUD.
        renderFilterPreviews(context, client, config);

        // Performance: these animations only run when the overlay is actually showing.
        blinkTick++;
        updateTapeCounter(manager);
        updateAudioLevel();

        // Performance: cache the style lookup to avoid redundant config access.
        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud != null
                ? config.overlayStyleHud : RecordableConfig.OverlayStyleHud.CLASSIC;

        switch (style) {
            case CLASSIC -> { if (config.hudClassicVisible) renderClassicOverlay(context, client, config, manager); }
            case VHS -> renderVhsLayered(context, client, config, manager, style);
            case SYNTHWAVE -> { if (config.hudSynthVisible) renderSynthwaveOverlay(context, client, config, manager); }
            case NONE -> {} // nothing
        }
    }

    private static void renderMousePointer(DrawContext context, MinecraftClient client,
                                           RecordableConfig config, RecordingManager manager,
                                           int explicitMouseX, int explicitMouseY) {
        if (context == null || client == null || config == null || manager == null) return;
        if (!config.showMousePointer || !manager.isActiveOrStopping()) return;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int x = explicitMouseX >= 0 ? explicitMouseX : (sw / 2);
        int y = explicitMouseY >= 0 ? explicitMouseY : (sh / 2);
        float pointerScale = Math.max(0.5f, Math.min(3.0f, (config.mousePointerScale / 100.0f) * POINTER_VISUAL_SCALE_FACTOR));
        int drawWidth = Math.max(1, Math.round(OS_CURSOR_BASE_WIDTH * pointerScale));
        int drawHeight = Math.max(1, Math.round(OS_CURSOR_BASE_HEIGHT * pointerScale));
        int maxX = Math.max(0, sw - drawWidth);
        int maxY = Math.max(0, sh - drawHeight);
        int drawX = Math.max(0, Math.min(maxX, x));
        int drawY = Math.max(0, Math.min(maxY, y));
        int tintArgb = resolvePointerTintArgb(config.mousePointerTheme);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, OS_CURSOR_TEXTURE, drawX, drawY, 0.0f, 0.0f,
                drawWidth, drawHeight, OS_CURSOR_BASE_WIDTH, OS_CURSOR_BASE_HEIGHT,
                OS_CURSOR_BASE_WIDTH, OS_CURSOR_BASE_HEIGHT, tintArgb);
    }

    private static int resolvePointerTintArgb(RecordableConfig.MousePointerTheme theme) {
        if (theme == null) {
            return 0xFFFFFFFF;
        }
        return switch (theme) {
            case EDGY_DARK -> 0xFF2E2E2E;
            case BOOTLEG_TOY_PHONE -> 0xFFFF69B4;
            case CLASSIC -> 0xFFFFFFFF;
        };
    }

    /**
     * V1-0.08 Streamer Mode censor display.
     *
     * <p><b>Before recording:</b> draws the censor regions as on-screen outlines
     * when "Show Preview" is enabled, so the streamer can position the blocks
     * before going live.</p>
     *
     * <p><b>While recording:</b> the "Bake in Overlay" setting
     * ({@link RecordableConfig#bakeInOverlay}) controls whether the censor is
     * baked into the saved video (see {@link StreamerModeManager#applyCensoring}):</p>
     * <ul>
     *   <li><b>OFF</b> (default): the recording is clean (no censor in the saved file).
     *       Instead the censor shows as a live on-screen overlay that obstructs scoreboards,
     *       coordinates, GUI elements and open inventories. It is shown by default and can be
     *       hidden with the "Toggle Censor Overlay" hotkey.</li>
     *   <li><b>ON</b>: the censor is baked into the recording. It may also appear on
     *       your live screen (controlled separately by "Show Preview").</li>
     * </ul>
     */
    private static void renderCensorPreview(DrawContext context, MinecraftClient client,
                                            RecordableConfig config, RecordingManager manager) {
        if (config == null || !config.streamerModeEnabled) return;
        // Only show censor overlay when actually in a world/server, not on main menu or loading screens
        if (client == null || client.world == null) return;
        boolean recording = manager != null && manager.isActiveOrStopping();
        if (config.bakeInOverlay) {
            // ON: the censor is baked into the recording. On the live screen it
            // shows only as a positioning guide when "Show Preview" is enabled.
            if (!config.streamerShowCensorPreview) return;
        } else {
            // OFF: the censor is a live on-screen overlay (not baked into the
            // recording). It is shown by default and can be hidden with the
            // "Toggle Censor Overlay" hotkey.
            if (config.censorOverlayHidden) return;
        }
        drawCensorRegions(context, client, config);
    }

    /**
     * Draws the configured censor regions (filled blocks + border + label) onto
     * the given context. Shared by the HUD overlay and the screen-level overlay
     * (so the censor also obstructs open inventories / GUI screens). Layered:
     * every enabled region is drawn so they stack on top of each other.
     */
    private static void drawCensorRegions(DrawContext context, MinecraftClient client,
                                          RecordableConfig config) {
        java.util.List<CensorRegion> regions = config.censorRegions;
        if (regions == null || regions.isEmpty()) return;
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        net.minecraft.client.font.TextRenderer tr = client.textRenderer;
        for (CensorRegion r : regions) {
            if (r == null || !r.enabled) continue;
            int x0 = (int) (r.x * w);
            int y0 = (int) (r.y * h);
            int x1 = (int) ((r.x + r.width) * w);
            int y1 = (int) ((r.y + r.height) * h);
            int rgb = r.color & 0xFFFFFF;
            // Fully opaque censor (alpha 0xFF) so the live overlay matches what is
            // baked into the recording and completely hides whatever is behind it.
            if (r.style == CensorRegion.Style.GRADIENT) {
                context.fillGradient(x0, y0, x1, y1, 0xFF000000 | rgb, 0xFF000000 | (r.colorEnd & 0xFFFFFF));
            } else {
                context.fill(x0, y0, x1, y1, 0xFF000000 | rgb);
            }
            String tag = (r.showLabel && r.label != null && !r.label.isBlank()) ? r.label : r.style.name();
            RenderHelper.drawText(context, tr, net.minecraft.text.Text.literal("\u25CF " + tag),
                    x0 + 2, y0 + 2, 0xFFFFFFFF);
        }
    }



    /**
     * Draws the live-preview filter approximations as full-screen underlays.
     * Each enabled filter layer is rendered in configured layer order so the player
     * sees what the chosen filters look like in real time. Controlled by
     * {@link RecordableConfig#showFiltersLive} and per-filter eye toggles.
     */
    private static void renderFilterPreviews(DrawContext context, MinecraftClient client,
                                             RecordableConfig config) {
        if (!config.showFiltersLive) return;
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        for (String layerId : parseLayerOrder(config.hudLayerOrder)) {
            if (!RecordableConfig.isFilterLayer(layerId)) continue;
            if (!config.isElementVisible(layerId)) continue;
            dev.recordable.filter.FilterType type = RecordableConfig.filterLayerToType(layerId);
            FilterPreviewRenderer.render(context, w, h, type, config.getFilterIntensity(layerId));
        }
    }

    /**
     * Draws a small microphone status indicator (top-centre) while a recording's
     * microphone stream is active. In always-on mode it shows a steady "MIC"; in
     * Push-to-Talk mode it lights up only while the key is held, and shows a dim
     * "MIC (PTT)" hint while idle so the player knows PTT is armed.
     */
    private static void renderMicIndicator(DrawContext context, MinecraftClient client) {
        if (!MicrophoneState.isMicCapturing()) return;
        RecordableConfig config = RecordableConfig.get();
        if (!config.hudMicVisible) return;

        TextRenderer tr = client.textRenderer;
        boolean live = MicrophoneState.isMicActiveForDisplay();
        boolean ptt = MicrophoneState.isPushToTalkMode();

        String label = live ? "\uD83C\uDFA4 MIC" : "\uD83C\uDFA4 MIC (PTT)";
        int textColor = live ? 0xFFFFFFFF : 0xFF888888;
        int dotColor = live ? 0xFFFF3030 : 0xFF555555;

        int textW = tr.getWidth(label);
        int panelW = textW + 16;
        int sw = client.getWindow().getScaledWidth();
        int x = config.hudMicX < 0 ? (sw - panelW) / 2 : config.hudMicX;
        // Keep the mic indicator below the status bar / camera cutout on Android.
        int y = Math.max(config.hudMicY, safeTopInset(client.getWindow().getScaledHeight()));
        int op = config.hudMicOpacity;

        context.fill(x - 2, y - 2, x + panelW, y + 11, RecordableConfig.applyOpacity(0x99000000, op));
        context.fill(x + 2, y + 2, x + 8, y + 8, RecordableConfig.applyOpacity(dotColor, op));
        RenderHelper.drawText(context, tr, Text.literal(label), x + 12, y + 1, RecordableConfig.applyOpacity(textColor, op));
        if (ptt && live) {
            context.fill(x - 2, y + 10, x + panelW, y + 11, RecordableConfig.applyOpacity(0xFFFF3030, op));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Android safe-area helpers
    // ═════════════════════════════════════════════════════════════════════════
    //
    // Phones running Pojav/Zalith use tall 20:9 displays (e.g. 1200×540) with
    // status bars, hole-punch cameras, and on-screen controller buttons that eat
    // into the screen edges. Fixed 4-14px offsets that look fine on a 16:9 desktop
    // place the REC indicator / timer / watermarks behind those system elements.
    // These helpers return a percentage-based inset on Android (and the unchanged
    // desktop default elsewhere), so the overlay stays inside the safe area.

    /** Corner inset: max(desktopDefault, 5% of the smaller scaled dimension) on Android. */
    private static int safeCornerInset(int scaledW, int scaledH, int desktopDefault) {
        if (!PlatformUtils.isAndroid()) return desktopDefault;
        return Math.max(desktopDefault, (int) (Math.min(scaledW, scaledH) * 0.05f));
    }

    /** Top inset (status bar / notch): 6% of scaled height on Android, else 0. */
    private static int safeTopInset(int scaledH) {
        return PlatformUtils.isAndroid() ? (int) (scaledH * 0.06f) : 0;
    }

    /** Side inset (camera cutout / rounded corners): 4% of scaled width on Android, else 0. */
    private static int safeSideInset(int scaledW) {
        return PlatformUtils.isAndroid() ? (int) (scaledW * 0.04f) : 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CLASSIC - Original Record-able info panel
    // ═════════════════════════════════════════════════════════════════════════

    private static void renderClassicOverlay(DrawContext context, MinecraftClient client,
                                              RecordableConfig config, RecordingManager manager) {
        dev.recordable.theme.ThemeColors skin = config.activeOverlaySkinOrNull();
        int accentRgb = skin != null ? (skin.accent & 0xFFFFFF) : config.getOverlayColorRgb();
        int accentArgb = 0xFF000000 | accentRgb;
        int accentSoftArgb = 0xAA000000 | accentRgb;
        TextRenderer tr = client.textRenderer;

        String effectiveTime = RecordingManager.formatDuration(manager.getEffectiveRecordingMillis());
        boolean isPaused = manager.getState() == RecordingManager.State.PAUSED;
        boolean showBlink = isPaused && (blinkTick / 15) % 2 == 0;

        String firstLine;
        if (manager.getState() == RecordingManager.State.STOPPING) {
            firstLine = "REC stopping...";
        } else if (isPaused) {
            firstLine = showBlink ? "⏸ PAUSED " + effectiveTime : "  PAUSED " + effectiveTime;
        } else {
            firstLine = "REC " + effectiveTime;
        }

        long fileSizeBytes = manager.getCurrentFileSizeBytes();
        String fileSizeStr = fileSizeBytes > 0 ? RecordingManager.formatBytes(fileSizeBytes) : "starting...";
        String secondLine = manager.getRecordingFps() + " FPS  drop " + manager.getDroppedFrames();

        RecordingManager.QueueHealth queueHealth = manager.getQueueHealth();
        String queueState = switch (queueHealth) {
            case CRITICAL -> "DROPPING";
            case SLOW -> "SLOW";
            default -> "OK";
        };
        String thirdLine = manager.getRecordingWidth() + "x" + manager.getRecordingHeight()
                + "  Queue: " + manager.getQueueSize() + "/" + manager.getQueueCapacity()
                + " (" + queueState + ")";

        int queueColor = switch (queueHealth) {
            case CRITICAL -> 0xFFFF7070;
            case SLOW -> 0xFFFFD166;
            default -> 0xFF9BE28F;
        };
        int fileSizeColor = fileSizeBytes > 0L ? 0xFFFFFFFF : 0xFFAAAAAA;
        int pausedColor = isPaused ? 0xFFFFD166 : 0xFFFFFFFF;

        boolean showTimer = config.showRecordingTimer;

        float scale = Math.max(0.5F, Math.min(2.0F, config.overlayScale / 100.0F));

        HudMat.push(context);
        HudMat.scale(context, scale, scale);

        int scaledW = (int) (client.getWindow().getScaledWidth() / scale);
        int scaledH = (int) (client.getWindow().getScaledHeight() / scale);
        int margin = safeCornerInset(scaledW, scaledH, 10);

        int lineCount = 3;
        if (ReplayBuffer.getInstance().isActive()) lineCount++;

        int maxTextWidth = Math.max(tr.getWidth(firstLine),
                Math.max(tr.getWidth(secondLine),
                        Math.max(tr.getWidth("Size: " + fileSizeStr), tr.getWidth(thirdLine))));
        int panelWidth = maxTextWidth + 22;
        int panelHeight = 12 + (lineCount * 11);

        // Position resolves via shared helper so the editor preview matches in-game placement.
        // Honors absolute hudClassicX/Y (set by dragging in the element editor), else
        // derives from overlayPosition.
        int[] cpos = config.classicPanelPos(scaledW, scaledH, panelWidth, panelHeight, margin);
        int x = cpos[0], y = cpos[1];

        // Background panel
        int panelBg = skin != null ? skin.panelBackground : 0x99000000;
        context.fill(x - 3, y - 3, x + panelWidth, y + panelHeight, panelBg);
        context.fill(x - 3, y - 3, x + panelWidth, y - 2, accentSoftArgb);

        if (!isPaused) {
            int recMarkerTop = y + Math.max(0, (tr.fontHeight - 8) / 2);
            context.fill(x, recMarkerTop, x + 8, recMarkerTop + 8, accentArgb);
        }

        int lineY = y;
        RenderHelper.drawText(context, tr, Text.literal(firstLine), x + (isPaused ? 0 : 13), lineY, pausedColor);
        lineY += 12;
        RenderHelper.drawText(context, tr, Text.literal(secondLine), x, lineY, skin != null ? skin.textSecondary : 0xFFE0E0E0);
        lineY += 11;
        RenderHelper.drawText(context, tr, Text.literal("Size: " + fileSizeStr), x, lineY, fileSizeColor);
        lineY += 11;
        RenderHelper.drawText(context, tr, Text.literal(thirdLine), x, lineY, queueColor);
        lineY += 11;

        if (ReplayBuffer.getInstance().isActive()) {
            ReplayBuffer rb = ReplayBuffer.getInstance();
            String rbLine = "⟳ Replay: " + rb.getBufferedSeconds() + "s buffered ("
                    + rb.getBufferedFrameCount() + " frames)";
            RenderHelper.drawText(context, tr, Text.literal(rbLine), x, lineY, 0xFF88CCFF);
        }

        HudMat.pop(context);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VHS Layered - Unified renderer that respects layer order and per-element opacity
    // ═════════════════════════════════════════════════════════════════════════

    private static void renderVhsLayered(DrawContext context, MinecraftClient client,
                                          RecordableConfig config, RecordingManager manager,
                                          RecordableConfig.OverlayStyleHud style) {
        float scale = Math.max(0.5F, Math.min(2.0F, config.overlayScale / 100.0F));
        HudMat.push(context);
        HudMat.scale(context, scale, scale);

        int w = (int) (client.getWindow().getScaledWidth() / scale);
        int h = (int) (client.getWindow().getScaledHeight() / scale);
        TextRenderer tr = client.textRenderer;

        // Overlay skin: when enabled, the VHS element colors follow the active UI Theme
        // palette instead of the per-element configured colors.
        dev.recordable.theme.ThemeColors skin = config.activeOverlaySkinOrNull();

        // Parse layer order
        List<String> layerOrder = parseLayerOrder(config.hudLayerOrder);

        // Render each element in layer order (first = bottom, last = top)
        for (String layerId : layerOrder) {
            if (!config.isElementVisible(layerId)) continue; // skip hidden elements
            switch (layerId) {
                case "Corners" -> {
                    if (config.vhsShowBrackets) {
                        int c = skin != null ? skin.accent : RecordableConfig.parseArgbColor(config.vhsBracketColor, 0xC8FFFFFF);
                        c = RecordableConfig.applyOpacity(c, config.hudCornersOpacity);
                        drawCornerBrackets(context, config.hudCornersX, config.hudCornersY,
                                config.hudCornersX + config.hudCornersWidth,
                                config.hudCornersY + config.hudCornersHeight, 20, 2, c);
                    }
                }
                case "PLAY/REC" -> {
                    int opacity = config.hudPlayRecOpacity;
                    int leftInset = Math.max(config.hudPlayRecX, safeSideInset(w));
                    int topInset = Math.max(config.hudPlayRecY, safeTopInset(h));
                    boolean recDotVisible = (blinkTick / 15) % 2 == 0;

                    if (config.vhsShowPlay) {
                        int pc = skin != null ? skin.textPrimary : RecordableConfig.parseArgbColor(config.vhsPlayColor, 0xFFFFFFFF);
                        RenderHelper.drawText(context, tr, Text.literal("PLAY \u25B6"),
                                leftInset, topInset, RecordableConfig.applyOpacity(pc, opacity));
                    }
                    int recY = topInset + (config.vhsShowPlay ? 12 : 0);
                    if (recDotVisible) {
                        int dc = skin != null ? skin.accent : RecordableConfig.parseArgbColor(config.vhsRecDotColor, 0xFFCC1E1E);
                        dc = RecordableConfig.applyOpacity(dc, opacity);
                        context.fill(leftInset, recY + 2, leftInset + 7, recY + 9, dc);
                    }
                    int rc = skin != null ? skin.textPrimary : RecordableConfig.parseArgbColor(config.vhsRecTextColor, 0xFFFFFFFF);
                    RenderHelper.drawText(context, tr, Text.literal("REC"),
                            leftInset + 10, recY, RecordableConfig.applyOpacity(rc, opacity));
                }
                case "Timestamp" -> {
                    int tc = skin != null ? skin.textPrimary : RecordableConfig.parseArgbColor(config.vhsTimestampColor, 0xFFFFFFFF);
                    tc = RecordableConfig.applyOpacity(tc, config.hudTimestampOpacity);
                    String timer = formatTimer(manager.getEffectiveRecordingMillis());
                    int timerW = tr.getWidth(timer);
                    int tsRight = Math.max(config.hudTimestampOffsetX, safeSideInset(w));
                    int tsTop = Math.max(config.hudTimestampY, safeTopInset(h));
                    RenderHelper.drawText(context, tr, Text.literal(timer),
                            w - tsRight - timerW, tsTop, tc);
                }
                case "SP" -> {
                    if (config.vhsShowSp) {
                        int sc = skin != null ? skin.textPrimary : RecordableConfig.parseArgbColor(config.vhsSpColor, 0xFFFFFFFF);
                        sc = RecordableConfig.applyOpacity(sc, config.hudSpOpacity);
                        RenderHelper.drawText(context, tr, Text.literal("SP"),
                                config.hudSpX, h - config.hudSpOffsetY, sc);
                    }
                }
                case "Details" -> {
                    renderVhsDetails(context, tr, config, manager, w, h);
                }
                case "Perf" -> {
                    if (config.showPerformanceStats) {
                        renderPerformanceStatsInner(context, tr, config, manager, w, h);
                    }
                }
            }
        }

        // Always-visible basic performance line (FPS, dropped frames, queue
        // status), built into the VHS overlay like the Classic overlay shows.
        // This is intentionally NOT gated by config.showPerformanceStats - that
        // option only controls the larger detailed Perf panel above.
        renderVhsPerfLine(context, tr, config, manager, w, h);

        HudMat.pop(context);
    }

    /** Parse comma-separated layer order, filling in any missing elements at the end. */
    private static List<String> parseLayerOrder(String order) {
        // Use the canonical layer registry (RecordableConfig.allLayerIds) so ALL
        // recognized HUD layers are accepted, including the Mic indicator.
        List<String> all = RecordableConfig.allLayerIds();
        java.util.Set<String> validSet = new java.util.HashSet<>(all);
        List<String> result = new ArrayList<>();
        if (order != null && !order.isBlank()) {
            for (String part : order.split(",")) {
                String id = part.trim();
                // Only accept recognized layer IDs - skip removed/unknown entries
                if (!id.isEmpty() && validSet.contains(id) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        // Add any missing elements at the end (forward-compat with new layers)
        for (String id : all) {
            if (!result.contains(id)) result.add(id);
        }
        return result;
    }

    /**
     * Render a compact, always-visible performance line for the VHS overlays
     * (FPS · dropped frames · queue status), mirroring the live diagnostics the
     * Classic overlay shows. Drawn at the bottom-left corner with a subtle
     * backing for legibility. Always rendered for VHS styles regardless of the
     * {@code showPerformanceStats} option (which only toggles the larger panel).
     */
    private static void renderVhsPerfLine(DrawContext context, TextRenderer tr,
                                          RecordableConfig config, RecordingManager manager,
                                          int w, int h) {
        RecordingManager.QueueHealth queueHealth = manager.getQueueHealth();
        String queueState = switch (queueHealth) {
            case CRITICAL -> "DROPPING";
            case SLOW -> "SLOW";
            default -> "OK";
        };
        int queueColor = switch (queueHealth) {
            case CRITICAL -> 0xFFFF7070;
            case SLOW -> 0xFFFFD166;
            default -> 0xFF9BE28F;
        };

        String fpsPart = manager.getRecordingFps() + " FPS  drop " + manager.getDroppedFrames() + "  ";
        String queuePart = "Q " + manager.getQueueSize() + "/" + manager.getQueueCapacity() + " " + queueState;
        int fpsW = tr.getWidth(fpsPart);
        int totalW = fpsW + tr.getWidth(queuePart);

        int x = 4;
        int y = h - 11;
        // Subtle backing so the stats stay readable over bright scenes.
        context.fill(x - 2, y - 2, x + totalW + 2, y + 9, 0x88000000);
        RenderHelper.drawText(context, tr, Text.literal(fpsPart), x, y, 0xFFE0E0E0);
        RenderHelper.drawText(context, tr, Text.literal(queuePart), x + fpsW, y, queueColor);
    }

    /** Render VHS details cluster (date, tape counter, audio meter, battery) with per-element opacity. */
    private static void renderVhsDetails(DrawContext context, TextRenderer tr,
                                          RecordableConfig config, RecordingManager manager,
                                          int w, int h) {
        int opacity = config.hudDetailsOpacity;
        int bottomRightX = w - config.hudDetailsOffsetX;
        int bottomY = h - config.hudDetailsOffsetY;
        int detailY = bottomY;

        int dateColor = RecordableConfig.parseArgbColor(config.vhsDateColor, 0xFFFFFFFF);
        dateColor = RecordableConfig.applyOpacity(dateColor, opacity);
        refreshHudDetailCaches();

        if (config.vhsShowDate) {
            int dateW = tr.getWidth(cachedDateLine);
            int timeW = tr.getWidth(cachedTimeLine);
            detailY -= 22;
            RenderHelper.drawText(context, tr, Text.literal(cachedTimeLine),
                    bottomRightX - timeW, detailY, dateColor);
            detailY += 11;
            RenderHelper.drawText(context, tr, Text.literal(cachedDateLine),
                    bottomRightX - dateW, detailY, dateColor);
            detailY = bottomY - 22 - 4;
        } else {
            detailY -= 4;
        }

        if (config.vhsShowTapeCounter) {
            int tcW = tr.getWidth(cachedTapeLine);
            detailY -= 11;
            RenderHelper.drawText(context, tr, Text.literal(cachedTapeLine), bottomRightX - tcW, detailY,
                    RecordableConfig.applyOpacity(0xFFCCCCCC, opacity));
        }

        if (config.vhsShowAudioMeter) {
            detailY -= 12;
            drawAudioMeter(context, bottomRightX - 60, detailY, 55, 8);
        }

        if (config.vhsShowBattery) {
            detailY -= 13;
            drawBatteryIndicator(context, tr, bottomRightX - 50, detailY, manager);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SYNTHWAVE - neon-bordered panel with retro-futuristic REC line
    // ═════════════════════════════════════════════════════════════════════════
    private static void renderSynthwaveOverlay(DrawContext context, MinecraftClient client,
                                               RecordableConfig config, RecordingManager manager) {
        float scale = Math.max(0.5F, Math.min(2.0F, config.overlayScale / 100.0F));
        HudMat.push(context);
        HudMat.scale(context, scale, scale);
        int w = (int) (client.getWindow().getScaledWidth() / scale);
        int h = (int) (client.getWindow().getScaledHeight() / scale);
        TextRenderer tr = client.textRenderer;

        String timer = formatTimer(manager.getEffectiveRecordingMillis());
        boolean recDotVisible = (blinkTick / 15) % 2 == 0;

        dev.recordable.theme.ThemeColors skin = config.activeOverlaySkinOrNull();
        final int MAGENTA = skin != null ? skin.accent : 0xFFFF2D95;
        final int CYAN = skin != null ? skin.accentHover : 0xFF00E5FF;
        final int panelBg = skin != null ? skin.panelBackground : 0xE61A0B2E;
        final int textColor = skin != null ? skin.textPrimary : CYAN;
        String line = "REC " + timer;
        int pw = tr.getWidth(line) + 22;
        int ph = 16;
        // Honors absolute hudSynthX/Y from the element editor, else default top-left inset.
        int[] spos = config.synthPanelPos(w, h, pw, ph, safeSideInset(w), safeTopInset(h));
        int x = spos[0], y = spos[1];

        context.fill(x, y, x + pw, y + ph, panelBg);         // panel
        context.fill(x, y, x + pw, y + 1, MAGENTA);          // neon top edge
        context.fill(x, y + ph - 1, x + pw, y + ph, CYAN);   // neon bottom edge
        context.fill(x, y, x + 1, y + ph, MAGENTA);          // left edge
        context.fill(x + pw - 1, y, x + pw, y + ph, CYAN);   // right edge
        if (recDotVisible) context.fill(x + 6, y + 5, x + 12, y + 11, MAGENTA);
        RenderHelper.drawText(context, tr, Text.literal(line), x + 16, y + 4, textColor);

        HudMat.pop(context);
    }

    /** Render performance stats panel with per-element opacity. Called within scaled matrix. */
    private static void renderPerformanceStatsInner(DrawContext context, TextRenderer tr,
                                                     RecordableConfig config, RecordingManager manager,
                                                     int w, int h) {
        int opacity = config.hudPerfOpacity;
        PerformanceMetrics metrics = PerformanceMetrics.getInstance();
        metrics.updateQueueStats(manager.getQueueSize(), manager.getQueueCapacity());
        metrics.updateMemory(manager.getUsedMemoryMiB());
        metrics.updateFps(Math.round(manager.getCaptureFpsEstimate()),
                Math.round(manager.getEncoderFpsEstimate()));
        metrics.updateFileSize(manager.getCurrentFileSizeBytes());

        String[] lines = {
                "Cap " + Math.round(manager.getCaptureFpsEstimate())
                        + " | Enc " + Math.round(manager.getEncoderFpsEstimate()) + " FPS",
                "Mem " + manager.getUsedMemoryMiB() + " MiB"
                        + " | Drop " + manager.getAdaptiveDroppedFrames(),
                "Queue: " + manager.getQueueSize() + "/" + manager.getQueueCapacity()
                        + " | " + String.format("%.0f%%", metrics.getBufferHealthPercent())
        };

        int maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, tr.getWidth(line));

        int panelW = maxW + 10;
        int panelH = 8 + lines.length * 10;

        int px = w - panelW - config.hudPerfOffsetX;
        int py = h - panelH - config.hudPerfOffsetY;

        context.fill(px - 2, py - 2, px + panelW, py + panelH,
                RecordableConfig.applyOpacity(0x88000000, opacity));

        int ly = py;
        int[] colors = {0xFFCFCFCF, 0xFFB0B0B0, 0xFF9BE28F};
        for (int i = 0; i < lines.length; i++) {
            RenderHelper.drawText(context, tr, Text.literal(lines[i]), px + 2, ly,
                    RecordableConfig.applyOpacity(colors[i % colors.length], opacity));
            ly += 10;
        }
    }

    // Legacy wrapper for non-VHS styles that still call perf stats separately
    private static void renderPerformanceStats(DrawContext context, MinecraftClient client,
                                                RecordableConfig config, RecordingManager manager) {
        float scale = Math.max(0.5F, Math.min(2.0F, config.overlayScale / 100.0F));
        HudMat.push(context);
        HudMat.scale(context, scale, scale);

        int w = (int) (client.getWindow().getScaledWidth() / scale);
        int h = (int) (client.getWindow().getScaledHeight() / scale);
        TextRenderer tr = client.textRenderer;
        renderPerformanceStatsInner(context, tr, config, manager, w, h);

        HudMat.pop(context);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Drawing helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Draw corner brackets at the four corners of the given rectangle.
     */
    private static void drawCornerBrackets(DrawContext ctx, int x1, int y1, int x2, int y2,
                                            int len, int thick, int color) {
        // Top-left ┌
        ctx.fill(x1, y1, x1 + len, y1 + thick, color);
        ctx.fill(x1, y1, x1 + thick, y1 + len, color);
        // Top-right ┐
        ctx.fill(x2 - len, y1, x2, y1 + thick, color);
        ctx.fill(x2 - thick, y1, x2, y1 + len, color);
        // Bottom-left └
        ctx.fill(x1, y2 - thick, x1 + len, y2, color);
        ctx.fill(x1, y2 - len, x1 + thick, y2, color);
        // Bottom-right ┘
        ctx.fill(x2 - len, y2 - thick, x2, y2, color);
        ctx.fill(x2 - thick, y2 - len, x2, y2, color);
    }

    /**
     * Simple audio level meter: two horizontal bars (L/R) with animated levels.
     */
    private static void drawAudioMeter(DrawContext ctx, int x, int y, int barWidth, int totalHeight) {
        int barH = Math.max(1, totalHeight / 2 - 1);
        // Background bars
        ctx.fill(x, y, x + barWidth, y + barH, 0xFF333333);
        ctx.fill(x, y + barH + 1, x + barWidth, y + barH + 1 + barH, 0xFF333333);

        // Animated fill
        float level = simulatedAudioLevel;
        int fillW = Math.round(level * barWidth);
        float rLevel = Math.min(1f, level + (AUDIO_RNG.nextFloat() * 0.15f - 0.075f));
        int fillWR = Math.round(Math.max(0, rLevel) * barWidth);

        // Green → yellow → red gradient based on level
        int barColor = level < 0.6f ? 0xFF44CC44 : (level < 0.85f ? 0xFFCCCC44 : 0xFFCC4444);
        int barColorR = rLevel < 0.6f ? 0xFF44CC44 : (rLevel < 0.85f ? 0xFFCCCC44 : 0xFFCC4444);

        ctx.fill(x, y, x + fillW, y + barH, barColor);
        ctx.fill(x, y + barH + 1, x + fillWR, y + barH + 1 + barH, barColorR);

        // Labels
        // "L" and "R" labels omitted to keep compact; bars speak for themselves
    }

    /**
     * VHS-style battery indicator with fill level.
     */
    private static void drawBatteryIndicator(DrawContext ctx, TextRenderer tr, int x, int y,
                                              RecordingManager manager) {
        // Battery outline
        int bw = 24, bh = 10;
        ctx.fill(x, y, x + bw, y + bh, 0xFFAAAAAA); // outer shell
        ctx.fill(x + 1, y + 1, x + bw - 1, y + bh - 1, 0xFF222222); // inner
        ctx.fill(x + bw, y + 2, x + bw + 2, y + bh - 2, 0xFFAAAAAA); // nub

        // Fill level (simulate based on recording time - deplete over 2 hours)
        long elapsedMs = manager.getEffectiveRecordingMillis();
        float pct = Math.max(0.05f, 1.0f - (elapsedMs / (2f * 3600_000f)));
        int fillW = Math.round(pct * (bw - 4));
        int fillColor = pct > 0.3f ? 0xFF44CC44 : (pct > 0.1f ? 0xFFCCCC44 : 0xFFCC4444);
        ctx.fill(x + 2, y + 2, x + 2 + fillW, y + bh - 2, fillColor);

        // Percentage label next to battery
        String pctStr = Math.round(pct * 100) + "%";
        RenderHelper.drawText(ctx, tr, Text.literal(pctStr), x + bw + 5, y + 1, 0xFFCCCCCC);
    }

    /**
     * Format elapsed milliseconds as HH:MM:SS.
     */
    private static String formatTimer(long elapsedMs) {
        long totalSeconds = Math.max(0, elapsedMs / 1000);
        long hours = totalSeconds / 3600;
        long mins = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, mins, secs);
    }

    private static void refreshHudDetailCaches() {
        long nowMs = System.currentTimeMillis();
        long second = nowMs / 1000L;
        if (second != cachedDateSecond) {
            LocalDateTime now = LocalDateTime.now();
            cachedDateLine = DATE_FORMAT.format(now);
            cachedTimeLine = TIME_FORMAT_12H.format(now).toUpperCase(Locale.ROOT);
            cachedDateSecond = second;
        }
        if (cachedTapeCounter != tapeCounter) {
            cachedTapeLine = String.format(Locale.ROOT, "TC %04d", tapeCounter);
            cachedTapeCounter = tapeCounter;
        }
    }

    private static void updateTapeCounter(RecordingManager manager) {
        int currentSecond = (int) (manager.getEffectiveRecordingMillis() / 1000);
        if (currentSecond != lastTapeSecond) {
            lastTapeSecond = currentSecond;
            tapeCounter = currentSecond;
        }
    }

    private static void updateAudioLevel() {
        // Smooth random audio level simulation
        float target = 0.3f + AUDIO_RNG.nextFloat() * 0.5f;
        simulatedAudioLevel += (target - simulatedAudioLevel) * 0.15f;
        simulatedAudioLevel = Math.max(0.05f, Math.min(1.0f, simulatedAudioLevel));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Toast Notification
    // ═════════════════════════════════════════════════════════════════════════

    // Themed toast palette (matches the mod's UI mockup: a rounded dark-gray
    // panel with a light-gray raised bevel, a soft drop shadow, and clean light
    // gray text).
    private static final int TOAST_PANEL_FILL   = 0xFF4F4F4F; // dark gray body
    private static final int TOAST_BEVEL_LIGHT  = 0xFFA0A0A0; // light gray border / bevel
    private static final int TOAST_BEVEL_DARK   = 0xFF2E2E2E; // dark bevel edge
    private static final int TOAST_SHADOW       = 0x66000000; // soft drop shadow
    private static final int TOAST_TEXT_COLOR   = 0xFFD4D4D4; // light gray text
    private static final int TOAST_RADIUS        = 4;  // rounded-corner radius
    private static final int TOAST_PAD_X         = 8;  // horizontal text padding
    private static final int TOAST_PAD_Y         = 6;  // vertical text padding
    private static final int TOAST_BORDER        = 2;
    private static final int TOAST_MARGIN        = 10;

    /**
     * Renders the custom Record-able toast notifications from {@link ToastQueue}.
     *
     * <p>Each toast reproduces the design mockup: a rounded dark-gray panel with a
     * light-gray raised bevel, a soft drop shadow, and clean light gray text that
     * wraps within the borders. Toasts stack from the top-right of the screen and
     * slide in from the right, then slide off-screen to the right when expiring.</p>
     */
    private static void renderToastNotification(DrawContext context, MinecraftClient client,
                                                 RecordingManager manager) {
        java.util.List<ToastQueue.Entry> toasts = ToastQueue.active();
        if (toasts.isEmpty()) return;

        // Toasts render every display frame for smooth animation. Hiding them
        // from the recording is no longer attempted, so no frames are skipped.

        TextRenderer tr = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        long now = System.currentTimeMillis();

        // Pull colors from the active theme so toasts match the mod's look
        // (accent border + themed dark panel) instead of a fixed gray.
        dev.recordable.theme.ThemeColors tc = dev.recordable.theme.ThemeEngine.get().colors();
        int panelFill = 0xFF000000 | (tc.panelBackground & 0x00FFFFFF);
        int bevelLight = tc.accent;
        int bevelDark = tc.panelBorder;
        int textColor = tc.textPrimary;

        int stackY = 8 + TOAST_BORDER + 2;
        for (ToastQueue.Entry entry : toasts) {
            float alpha = entry.alpha(now);
            if (alpha <= 0.02f) continue;

            // Wrap text into a bounded-width panel.
            int maxTextWidth = Math.min(150, Math.max(60, tr.getWidth(entry.message) + 6));
            java.util.List<net.minecraft.text.OrderedText> lines =
                    tr.wrapLines(net.minecraft.text.Text.literal(entry.message), maxTextWidth);
            if (lines.isEmpty()) { stackY += 4; continue; }

            int textWidth = 0;
            for (net.minecraft.text.OrderedText line : lines) {
                textWidth = Math.max(textWidth, tr.getWidth(line));
            }
            int lineHeight = tr.fontHeight + 2;
            int panelWidth = textWidth + TOAST_PAD_X * 2;
            int panelHeight = lines.size() * lineHeight + TOAST_PAD_Y * 2;
            int panelX = screenWidth - panelWidth - TOAST_MARGIN;

            // Slide in horizontally from the right edge, easing into place.
            int slide = Math.round((1f - entry.slideProgress(now)) * 34f);
            panelX += slide;
            // After the display time, slide the whole toast off the right edge
            // of the screen so it disappears with a smooth motion.
            int slideOutTravel = panelWidth + TOAST_MARGIN + TOAST_BORDER + 8;
            panelX += Math.round(entry.slideOutProgress(now) * slideOutTravel);
            int panelY = stackY;

            // Soft drop shadow (offset down-right) for the raised look.
            fillRoundedRect(context, panelX + 2, panelY + 3,
                    panelX + panelWidth + 2, panelY + panelHeight + 3,
                    TOAST_RADIUS, withAlpha(TOAST_SHADOW, alpha));
            // Dark bevel edge peeking out at the bottom-right.
            fillRoundedRect(context, panelX - TOAST_BORDER + 1, panelY - TOAST_BORDER + 1,
                    panelX + panelWidth + TOAST_BORDER, panelY + panelHeight + TOAST_BORDER,
                    TOAST_RADIUS + 1, withAlpha(bevelDark, alpha));
            // Accent bevel / border around the panel (themed highlight).
            fillRoundedRect(context, panelX - TOAST_BORDER, panelY - TOAST_BORDER,
                    panelX + panelWidth + TOAST_BORDER - 1, panelY + panelHeight + TOAST_BORDER - 1,
                    TOAST_RADIUS + 1, withAlpha(bevelLight, alpha));
            // Themed panel body.
            fillRoundedRect(context, panelX, panelY,
                    panelX + panelWidth, panelY + panelHeight,
                    TOAST_RADIUS, withAlpha(panelFill, alpha));

            // Left-aligned text lines inside the padding (no shadow, clean look).
            int textX = panelX + TOAST_PAD_X;
            int textY = panelY + TOAST_PAD_Y;
            for (net.minecraft.text.OrderedText line : lines) {
                RenderHelper.drawText(context, tr, line, textX, textY, withAlpha(textColor, alpha), false);
                textY += lineHeight;
            }

            stackY += panelHeight + TOAST_BORDER * 2 + 8;
        }
    }

    /**
     * Fills a rounded rectangle by drawing per-row horizontal spans and insetting
     * the corner rows to approximate a circular arc of the given radius.
     */
    private static void fillRoundedRect(DrawContext context, int x0, int y0,
                                        int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0) return;
        int r = Math.max(0, Math.min(radius, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
        for (int y = y0; y < y1; y++) {
            int d = Math.min(y - y0, (y1 - 1) - y);
            int inset = 0;
            if (d < r) {
                double dy = r - d - 0.5;
                double val = (double) r * r - dy * dy;
                inset = (int) Math.round(r - (val > 0 ? Math.sqrt(val) : 0));
            }
            context.fill(x0 + inset, y, x1 - inset, y + 1, color);
        }
    }

    /** Multiplies the alpha channel of an ARGB color by the given factor (0..1). */
    private static int withAlpha(int argb, float factor) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Watermark / Branding rendering (V1-0.06 Feature 4)
    // ═══════════════════════════════════════════════════════════════════

    /** Animation time anchor (wall clock) for live, non-recording preview. */
    private static long watermarkAnchorMs = 0L;

    /**
     * Renders all enabled TEXT watermarks. While recording, the frame-capture hook
     * runs after the HUD, so anything drawn here is baked into the recorded video.
     * When not recording, watermarks render only if live preview is enabled.
     */
    static void renderWatermarks(DrawContext context, MinecraftClient client,
                                 RecordableConfig config, RecordingManager manager) {
        if (config == null || !config.watermarksEnabled) return;
        java.util.List<WatermarkSlot> slots = config.watermarkSlots;
        if (slots == null || slots.isEmpty()) return;

        boolean recording = manager != null && manager.isActiveOrStopping();
        if (!recording && !config.showWatermarksLive) return;

        TextRenderer tr = client.textRenderer;
        if (tr == null) return;

        long nowMs = System.currentTimeMillis();
        double tMs;
        if (recording) {
            watermarkAnchorMs = 0L;
            tMs = manager.getEffectiveRecordingMillis();
        } else {
            if (watermarkAnchorMs == 0L) watermarkAnchorMs = nowMs;
            tMs = nowMs - watermarkAnchorMs;
        }
        double recSec = tMs / 1000.0;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        String username = (client.player != null) ? client.player.getName().getString() : "Player";

        for (WatermarkSlot slot : slots) {
            if (slot == null || !slot.enabled) continue;
            if (recording && !slot.visibleAt(recSec)) continue;
            try {
                if (slot.kind == WatermarkSlot.Kind.IMAGE) {
                    renderImageWatermark(context, client, slot, screenW, screenH, tMs);
                } else {
                    renderTextWatermark(context, tr, slot, screenW, screenH, username, tMs);
                }
            } catch (Throwable t) {
                // Promoted debug -> warn: image-watermark render failures were previously
                // swallowed silently (text watermarks worked, images failed invisibly),
                // which made the rendering bug impossible to diagnose from logs.
                RecordableMod.LOGGER.warn("[Record-able] watermark render failed for slot kind {}: {}",
                        slot.kind, t.toString());
            }
        }
    }

    /** Cached uploaded watermark texture (GPU id + intrinsic pixel size). */
    private static final class CachedWatermarkTexture {
        final Identifier id;
        final int width;
        final int height;
        CachedWatermarkTexture(Identifier id, int width, int height) {
            this.id = id; this.width = width; this.height = height;
        }
    }

    /** filename -> uploaded texture. A null value means a prior load failed (don't retry every frame). */
    private static final java.util.Map<String, CachedWatermarkTexture> WATERMARK_TEXTURES =
            new java.util.HashMap<>();

    /** Load (once) and return the GPU texture for a watermark image filename, or null if unavailable. */
    private static CachedWatermarkTexture loadWatermarkTexture(MinecraftClient client, String filename) {
        if (filename == null || filename.isBlank()) return null;
        if (WATERMARK_TEXTURES.containsKey(filename)) return WATERMARK_TEXTURES.get(filename);
        CachedWatermarkTexture result = null;
        try {
            java.nio.file.Path path = WatermarkImageStore.resolve(filename);
            if (path != null && java.nio.file.Files.isRegularFile(path)) {
                // NativeImage.read() is PNG-only; normalise JPG/JPEG to PNG bytes first
                // so user-selected .jpg watermarks load instead of failing invisibly.
                byte[] pngBytes = WatermarkImageStore.readAsPngBytes(path);
                if (pngBytes == null) {
                    WATERMARK_TEXTURES.put(filename, null);
                    return null;
                }
                NativeImage image;
                try (java.io.InputStream in = new java.io.ByteArrayInputStream(pngBytes)) {
                    image = NativeImage.read(in);
                }
                NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "recordable-watermark", image);
                String safe = "watermark/" + Integer.toHexString(filename.hashCode() & 0x7FFFFFFF);
                Identifier id = VersionHelper.id(RecordableMod.MOD_ID, safe);
                client.getTextureManager().registerTexture(id, tex);
                result = new CachedWatermarkTexture(id, image.getWidth(), image.getHeight());
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[Record-able] failed to load watermark image '{}': {}", filename, t.toString());
        }
        WATERMARK_TEXTURES.put(filename, result);
        return result;
    }

    private static void renderImageWatermark(DrawContext context, MinecraftClient client,
            WatermarkSlot slot, int screenW, int screenH, double tMs) {
        CachedWatermarkTexture tex = loadWatermarkTexture(client, slot.imagePath);
        if (tex == null || tex.width <= 0 || tex.height <= 0) return;

        float animAlpha = 1.0f;
        float slideX = 0f;
        double durMs = Math.max(1, slot.animationDurationMs);
        switch (slot.animation) {
            case FADE -> animAlpha = (float) Math.min(1.0, tMs / durMs);
            case PULSE -> {
                double phase = (tMs % durMs) / durMs;
                animAlpha = 0.45f + 0.55f * (float) ((1.0 + Math.sin(phase * 2.0 * Math.PI)) / 2.0);
            }
            case SLIDE -> {
                double p = Math.min(1.0, tMs / durMs);
                slideX = (float) ((1.0 - p) * 60.0);
            }
            case NONE -> { }
        }

        float opacityFactor = Math.max(0f, Math.min(1f, slot.opacity / 100.0f));
        int finalAlpha = (int) Math.round(255 * opacityFactor * animAlpha);
        if (finalAlpha <= 2) return;
        finalAlpha = Math.min(255, finalAlpha);
        int tint = (finalAlpha << 24) | 0x00FFFFFF;

        float userScale = Math.max(0.05f, slot.scale / 100.0f);
        // Auto-fit base scale: shrink logos into a small "YouTube watermark"
        // bounding box (~100px, capped at ~9% of screen width), preserving the
        // aspect ratio. The user's scale slider then multiplies this base, so
        // 100% gives a tidy corner logo and 200% allows a larger one.
        float maxBox = Math.min(100.0f, screenW * 0.09f);
        float fit = Math.min(maxBox / tex.width, maxBox / tex.height);
        if (fit > 1.0f) fit = 1.0f; // never upscale the source image by default
        float scale = fit * userScale;
        int scaledW = Math.max(1, Math.round(tex.width * scale));
        int scaledH = Math.max(1, Math.round(tex.height * scale));

        float[] pos = computeWatermarkAnchor(slot, screenW, screenH, scaledW, scaledH);
        int x = Math.round(pos[0] + slideX);
        int y = Math.round(pos[1]);

        // Render via the SAME proven path that draws thumbnails in VideoCollectionScreen
        // (which renders correctly on 1.21.11): scale by the DESTINATION rectangle - the
        // textureWidth/textureHeight args are passed equal to the destination size so the
        // UVs span the whole image - and draw at integer screen coordinates. The previous
        // code drew the quad at the image's native pixel size and relied on a
        // Matrix3x2fStack scale to shrink it; that path failed to display image watermarks
        // in the 1.21.11 deferred GUI pipeline. The matrix stack is now used only for the
        // (uncommon) rotation case, rotating about the watermark's centre.
        if (slot.rotation != 0) {
            HudMat.push(context);
            float cx = x + scaledW / 2.0f;
            float cy = y + scaledH / 2.0f;
            HudMat.translate(context, cx, cy);
            HudMat.rotate(context, (float) Math.toRadians(slot.rotation));
            HudMat.translate(context, -cx, -cy);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id, x, y, 0.0f, 0.0f,
                    scaledW, scaledH, scaledW, scaledH, tint);
            HudMat.pop(context);
        } else {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id, x, y, 0.0f, 0.0f,
                    scaledW, scaledH, scaledW, scaledH, tint);
        }
    }

    private static void renderTextWatermark(DrawContext context, TextRenderer tr,
            WatermarkSlot slot, int screenW, int screenH, String username, double tMs) {
        String text = slot.resolveText(username);
        if (text == null || text.isEmpty()) return;

        java.util.List<String> colorStops = slot.effectiveColors();
        int baseColor = parseWatermarkColor(colorStops.get(0));
        int baseAlpha = (baseColor >>> 24) & 0xFF;
        if (baseAlpha == 0) baseAlpha = 255;

        float animAlpha = 1.0f;
        float slideX = 0f;
        double durMs = Math.max(1, slot.animationDurationMs);
        switch (slot.animation) {
            case FADE -> animAlpha = (float) Math.min(1.0, tMs / durMs);
            case PULSE -> {
                double phase = (tMs % durMs) / durMs;
                animAlpha = 0.45f + 0.55f * (float) ((1.0 + Math.sin(phase * 2.0 * Math.PI)) / 2.0);
            }
            case SLIDE -> {
                double p = Math.min(1.0, tMs / durMs);
                slideX = (float) ((1.0 - p) * 60.0);
            }
            case NONE -> { }
        }

        float opacityFactor = Math.max(0f, Math.min(1f, slot.opacity / 100.0f));
        int finalAlpha = (int) Math.round(baseAlpha * opacityFactor * animAlpha);
        if (finalAlpha <= 2) return;
        finalAlpha = Math.min(255, finalAlpha);
        int color = (finalAlpha << 24) | (baseColor & 0x00FFFFFF);

        float scale = Math.max(0.1f, slot.scale / 100.0f);
        int textW = tr.getWidth(text);
        int textH = 9;
        float scaledW = textW * scale;
        float scaledH = textH * scale;

        float[] pos = computeWatermarkAnchor(slot, screenW, screenH, scaledW, scaledH);
        float x = pos[0] + slideX;
        float y = pos[1];

        HudMat.push(context);
        HudMat.translate(context, x, y);
        if (slot.rotation != 0) {
            HudMat.translate(context, scaledW / 2.0f, scaledH / 2.0f);
            HudMat.rotate(context, (float) Math.toRadians(slot.rotation));
            HudMat.translate(context, -scaledW / 2.0f, -scaledH / 2.0f);
        }
        HudMat.scale(context, scale, scale);
        if (colorStops.size() <= 1) {
            context.drawText(tr, text, 0, 0, color, slot.textShadow);
        } else {
            drawGradientText(context, tr, text, finalAlpha, colorStops, slot.textShadow);
        }
        HudMat.pop(context);
    }

    /** Compute the top-left anchor (scaled GUI px) for a watermark of the given size. */
    private static float[] computeWatermarkAnchor(WatermarkSlot slot, int screenW, int screenH,
                                                  float w, float h) {
        int pad = Math.max(0, slot.padding);
        float x;
        float y;
        switch (slot.position) {
            case TOP_LEFT      -> { x = pad;                y = pad; }
            case TOP_CENTER    -> { x = (screenW - w) / 2f; y = pad; }
            case TOP_RIGHT     -> { x = screenW - w - pad;  y = pad; }
            case MIDDLE_LEFT   -> { x = pad;                y = (screenH - h) / 2f; }
            case CENTER        -> { x = (screenW - w) / 2f; y = (screenH - h) / 2f; }
            case MIDDLE_RIGHT  -> { x = screenW - w - pad;  y = (screenH - h) / 2f; }
            case BOTTOM_LEFT   -> { x = pad;                y = screenH - h - pad; }
            case BOTTOM_CENTER -> { x = (screenW - w) / 2f; y = screenH - h - pad; }
            case BOTTOM_RIGHT  -> { x = screenW - w - pad;  y = screenH - h - pad; }
            case CUSTOM        -> { x = slot.customX;       y = slot.customY; }
            default            -> { x = screenW - w - pad;  y = screenH - h - pad; }
        }
        return new float[]{ x, y };
    }

    /**
     * Render text with a horizontal multi-stop gradient interpolated across its width.
     * Each character is drawn individually with a color sampled at its horizontal center.
     */
    private static void drawGradientText(DrawContext context, TextRenderer tr, String text,
            int alpha, java.util.List<String> colorStops, boolean shadow) {
        int n = colorStops.size();
        int[][] stops = new int[n][];
        for (int i = 0; i < n; i++) {
            int argb = parseWatermarkColor(colorStops.get(i));
            stops[i] = new int[]{ (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF };
        }
        int totalW = Math.max(1, tr.getWidth(text));
        int penX = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            String ch = String.valueOf(text.charAt(i));
            int chW = tr.getWidth(ch);
            float frac = (penX + chW / 2.0f) / totalW;
            int rgb = sampleGradientRgb(stops, frac);
            int color = (alpha << 24) | (rgb & 0x00FFFFFF);
            context.drawText(tr, ch, penX, 0, color, shadow);
            penX += chW;
        }
    }

    /** Sample an RGB color from gradient stops at fraction t in [0,1]. */
    private static int sampleGradientRgb(int[][] stops, float t) {
        if (stops.length == 1) return (stops[0][0] << 16) | (stops[0][1] << 8) | stops[0][2];
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float scaled = t * (stops.length - 1);
        int idx = (int) Math.floor(scaled);
        if (idx >= stops.length - 1) idx = stops.length - 2;
        float local = scaled - idx;
        int[] a = stops[idx];
        int[] b = stops[idx + 1];
        int r = Math.round(a[0] + (b[0] - a[0]) * local);
        int g = Math.round(a[1] + (b[1] - a[1]) * local);
        int bl = Math.round(a[2] + (b[2] - a[2]) * local);
        return (r << 16) | (g << 8) | bl;
    }

    /** Parse "#RRGGBB" or "#AARRGGBB" into 0xAARRGGBB. Defaults to opaque white. */
    private static int parseWatermarkColor(String hex) {
        if (hex == null) return 0xFFFFFFFF;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) {
                return 0xFF000000 | (int) (Long.parseLong(s, 16) & 0xFFFFFF);
            } else if (s.length() == 8) {
                return (int) (Long.parseLong(s, 16) & 0xFFFFFFFFL);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0xFFFFFFFF;
    }
}
