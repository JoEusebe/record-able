package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.StorageManager;
import dev.recordable.VideoMetadata;
import dev.recordable.VideoShareUploader;
import dev.recordable.theme.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import dev.recordable.theme.CycleButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** In-game video browser and management UI for recorded files. */
public final class VideoCollectionScreen extends Screen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFE5E5E5;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private static final int ENTRY_HEIGHT = 62;
    private static final int BUTTON_WIDTH = 54;
    private static final int BUTTON_HEIGHT = 14;
    private static final int DELETE_CONFIRM_MS = 6_000;
    private static final String CLIP_EVENT_ALL = "__all__";
    private static final String CLIP_EVENT_HINDSIGHT = "hindsight mode";
    private static final List<String> DEFAULT_CLIP_EVENT_TYPES = List.of(
            "on totem pop", "kills", "deaths", CLIP_EVENT_HINDSIGHT, "custom_events", "legacy", "other");

    private final Screen parent;
    /** When true, this screen shows the separate "Clips" collection (auto-clips) instead of recordings. */
    private final boolean clipsMode;
    private final String initialClipEvent;
    private final boolean hindsightCategoryMode;
    private final List<VideoMetadata> allVideos = new ArrayList<>();
    private final List<VideoMetadata> filteredVideos = new ArrayList<>();
    private final List<ActionZone> actionZones = new ArrayList<>();
    private final Map<Path, ThumbnailTexture> thumbnailCache = new HashMap<>();
    private final Map<Path, String> clipEventByPath = new HashMap<>();
    private final List<String> clipEventOptions = new ArrayList<>();

    private EditBox searchField;
    private CycleButton clipEventButton;
    private String selectedClipEvent = CLIP_EVENT_ALL;
    private Component statusMessage;
    private boolean statusIsError;
    private int scrollOffset;
    private boolean draggingScrollbar;

    private int contentLeft;
    private int contentWidth;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int headerTop;

    private long totalSizeBytes;
    private Path deleteConfirmPath;
    private long deleteConfirmUntil;

    /** Background thread for probing durations without blocking the render thread. */
    private volatile ExecutorService durationProber;
    private final AtomicBoolean durationProbeRunning = new AtomicBoolean(false);

    /** The recording currently offered for sharing (non-null while the host-choice modal is open). */
    private VideoMetadata shareTarget;
    /** True while the retention (7/30/60 day) drawer is expanded next to the re.share-abl.ink button. */
    private boolean shareRetentionDrawerOpen;
    /** True while an upload is running so the modal can show progress and block re-entry. */
    private volatile boolean sharing;
    /** Background thread that performs the upload without blocking the render thread. */
    private volatile ExecutorService shareUploader;
    /** Short success text shown in the share overlay after upload finishes. */
    private Component shareOverlayMessage;
    /** Timestamp (ms) until which the success overlay stays visible. */
    private long shareOverlayUntilMs;

    public VideoCollectionScreen(Screen parent) {
        this(parent, false);
    }

    public VideoCollectionScreen(Screen parent, boolean clipsMode) {
        this(parent, clipsMode, null);
    }

    public VideoCollectionScreen(Screen parent, boolean clipsMode, String initialClipEvent) {
        super(Component.translatable(clipsMode
                ? "screen.recordable.video_collection.clips_title"
                : "screen.recordable.video_collection.title"));
        this.parent = parent instanceof VideoCollectionScreen previous ? previous.parent : parent;
        this.clipsMode = clipsMode;
        this.initialClipEvent = initialClipEvent;
        this.hindsightCategoryMode = clipsMode && CLIP_EVENT_HINDSIGHT.equals(initialClipEvent);
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        this.actionZones.clear();
        this.clipEventButton = null;

        this.contentWidth = Math.max(320, Math.min((int) (this.width * 0.92D), 980));
        this.contentLeft = (this.width - this.contentWidth) / 2;

        // Reserve a fixed header band at the very top for the panel title and
        // summary, then place the action buttons just below it. Anchoring the
        // title to a fixed top (rather than relative to the list) prevents the
        // title/summary from overlapping the buttons when the buttons wrap onto
        // additional rows (which happens on phones / large GUI scales).
        this.headerTop = Math.max(6, (int) (this.height * 0.025D));
        int topBarY = this.headerTop + 18;
        int rowLeft = this.contentLeft + 8;
        int rowRight = this.contentLeft + this.contentWidth - 8;
        int btnGap = 4;
        int btnH = 18;
        List<Integer> btnWidths = new ArrayList<>(List.of(74, 84, 130, 78, 110, 94));
        List<Component> btnLabels = new ArrayList<>(List.of(
                Component.translatable("screen.recordable.video_collection.back"),
                Component.translatable("screen.recordable.video_collection.settings"),
                Component.translatable("screen.recordable.video_collection.open_recordings_folder"),
                Component.translatable("screen.recordable.video_collection.refresh"),
                Component.translatable("screen.recordable.video_collection.sort")
                        .copy().append(Component.literal(": " + sortModeLabel(RecordableConfig.get().gallerySortMode))),
                Component.literal(categoryToggleLabel())
        ));
        List<Button.OnPress> btnActions = new ArrayList<>(List.of(
                button -> onClose(),
                button -> { if (this.minecraft != null) { this.minecraft.setScreenAndShow(new RecordableSettingsScreen(this)); } },
                button -> openRecordingsFolder(),
                button -> refreshVideos(),
                button -> cycleSortMode(),
                button -> cycleCategoryView(true)
        ));
        int sortButtonIndex = 4;
        int categoryButtonIndex = 5;
        int clipEventButtonIndex = -1;

        int totalBtnWidth = 0;
        for (int w : btnWidths) { totalBtnWidth += w; }
        totalBtnWidth += btnGap * (btnWidths.size() - 1);

        // Responsive top toolbar: the search bar has been removed, so the action
        // buttons use the full content width. They are right-aligned on a single
        // row when they fit, otherwise they wrap onto additional rows.
        // Index 2 is "Open Recordings Folder". On Android the launcher sandbox
        // cannot hand a folder to an external app, so it is shown disabled with a
        // tooltip - recordings are auto-saved to the gallery (Movies/Record-able).
        Tooltip androidFolderTip = Tooltip.create(Component.literal(
                "Not available on Android. Recordings are auto-saved to your gallery "
                        + "(Movies/Record-able) - open them from your Gallery or Files app."));

        int availWidth = rowRight - rowLeft;
        int lastRowY = topBarY;
        if (totalBtnWidth <= availWidth) {
            int x = rowRight;
            for (int i = btnWidths.size() - 1; i >= 0; i--) {
                int width = btnWidths.get(i);
                x -= width;
                Button btn;
                if (i == sortButtonIndex) {
                    btn = CycleButton.create(x, topBarY, width, btnH, btnLabels.get(i),
                            b -> cycleSortMode(true), b -> cycleSortMode(false));
                } else if (i == categoryButtonIndex) {
                    btn = CycleButton.create(x, topBarY, width, btnH, btnLabels.get(i),
                            b -> cycleCategoryView(true), b -> cycleCategoryView(false));
                } else {
                    btn = Button.builder(btnLabels.get(i), btnActions.get(i))
                            .bounds(x, topBarY, width, btnH).build();
                }
                if (i == 2 && PlatformUtils.isAndroid()) {
                    btn.active = false;
                    btn.setTooltip(androidFolderTip);
                }
                this.addRenderableWidget(btn);
                x -= btnGap;
            }
        } else {
            int x = rowLeft;
            int y = topBarY;
            for (int i = 0; i < btnWidths.size(); i++) {
                int width = btnWidths.get(i);
                if (x > rowLeft && x + width > rowRight) {
                    x = rowLeft;
                    y += btnH + btnGap;
                }
                Button btn;
                if (i == sortButtonIndex) {
                    btn = CycleButton.create(x, y, width, btnH, btnLabels.get(i),
                            b -> cycleSortMode(true), b -> cycleSortMode(false));
                } else if (i == categoryButtonIndex) {
                    btn = CycleButton.create(x, y, width, btnH, btnLabels.get(i),
                            b -> cycleCategoryView(true), b -> cycleCategoryView(false));
                } else {
                    btn = Button.builder(btnLabels.get(i), btnActions.get(i))
                            .bounds(x, y, width, btnH).build();
                }
                if (i == 2 && PlatformUtils.isAndroid()) {
                    btn.active = false;
                    btn.setTooltip(androidFolderTip);
                }
                this.addRenderableWidget(btn);
                x += width + btnGap;
            }
            lastRowY = y;
        }
        if (this.clipsMode && !this.hindsightCategoryMode) {
            syncSelectedClipEvent();
            int eventY = lastRowY + btnH + btnGap;
            CycleButton cycle = CycleButton.create(rowLeft, eventY, 112, btnH, Component.literal(clipEventButtonLabel()),
                    b -> cycleClipEvent(true), b -> cycleClipEvent(false));
            this.clipEventButton = cycle;
            this.addRenderableWidget(cycle);
            lastRowY = eventY;
        }

        this.listLeft = this.contentLeft + 8;
        this.listRight = this.contentLeft + this.contentWidth - 8;
        this.listTop = lastRowY + 28;
        this.listBottom = this.height - Math.max(24, (int) (this.height * 0.04D));

        refreshVideos();

    }

    /** Sort {@link #allVideos} according to the configured gallery sort mode. */
    private void sortAllVideos() {
        String mode = RecordableConfig.get().gallerySortMode;
        Comparator<VideoMetadata> cmp;
        switch (mode) {
            case "oldest" -> cmp = Comparator.comparingLong((VideoMetadata m) -> m.modifiedMillis);
            case "name_az" -> cmp = Comparator.comparing((VideoMetadata m) -> m.filename, String.CASE_INSENSITIVE_ORDER);
            case "name_za" -> cmp = Comparator.comparing((VideoMetadata m) -> m.filename, String.CASE_INSENSITIVE_ORDER).reversed();
            case "largest" -> cmp = Comparator.comparingLong((VideoMetadata m) -> m.sizeBytes).reversed();
            case "smallest" -> cmp = Comparator.comparingLong((VideoMetadata m) -> m.sizeBytes);
            case "longest" -> cmp = Comparator.comparingDouble((VideoMetadata m) -> m.durationSeconds).reversed();
            case "shortest" -> cmp = Comparator.comparingDouble((VideoMetadata m) -> m.durationSeconds);
            default -> cmp = Comparator.comparingLong((VideoMetadata m) -> m.modifiedMillis).reversed();
        }
        this.allVideos.sort(cmp);
    }

    private static String sortModeLabel(String mode) {
        return switch (mode) {
            case "oldest" -> "Oldest";
            case "name_az" -> "A-Z";
            case "name_za" -> "Z-A";
            case "largest" -> "Largest";
            case "smallest" -> "Smallest";
            case "longest" -> "Longest";
            case "shortest" -> "Shortest";
            default -> "Newest";
        };
    }

    private void cycleSortMode() {
        cycleSortMode(true);
    }

    private void cycleSortMode(boolean forward) {
        RecordableConfig config = RecordableConfig.get();
        String[] modes = RecordableConfig.GALLERY_SORT_MODES;
        int idx = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(config.gallerySortMode)) { idx = i; break; }
        }
        int step = forward ? 1 : -1;
        config.gallerySortMode = modes[(idx + step + modes.length) % modes.length];
        config.save();
        sortAllVideos();
        applyFilter();
        this.init();
    }

    private int addTopButton(int rightEdge, int y, int width, Component text, Button.OnPress action) {
        int x = rightEdge - width;
        this.addRenderableWidget(Button.builder(text, action).bounds(x, y, width, 18).build());
        return x - 4;
    }

    @Override public void onClose() {
        cancelDurationProbe();
        cancelShareUpload();
        clearThumbnails();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    private void cancelDurationProbe() {
        durationProbeRunning.set(false);
        ExecutorService exec = durationProber;
        if (exec != null) {
            exec.shutdownNow();
            durationProber = null;
        }
    }

    private void refreshVideos() {
        cancelDurationProbe();
        
        // Show loading state immediately for responsiveness
        this.allVideos.clear();
        this.totalSizeBytes = 0L;
        this.statusMessage = Component.translatable("screen.recordable.video_collection.loading");
        this.statusIsError = false;
        clearThumbnails();
        applyFilter();

        // Perform file scan in background to avoid blocking the main thread
        CompletableFuture.runAsync(() -> {
            List<VideoMetadata> scannedVideos = new ArrayList<>();
            Map<Path, String> scannedClipEvents = new HashMap<>();
            java.util.Set<String> scannedEventTypes = new java.util.LinkedHashSet<>();
            long totalBytes = 0L;
            Component errorMessage = null;
            boolean isError = false;

            try {
                RecordableConfig config = RecordableConfig.get();
                Path baseDir = config == null ? null : config.getOutputDirectory();
                List<Path> scanRoots = new ArrayList<>();
                if (baseDir != null) {
                    if (this.clipsMode) {
                        scanRoots.add(baseDir.resolve("clips"));
                        scanRoots.add(baseDir.resolve("recording_auto_clips"));
                    } else {
                        scanRoots.add(baseDir);
                    }
                }

                boolean scannedAnyDirectory = false;
                for (Path scanDir : scanRoots) {
                    if (scanDir == null || !Files.exists(scanDir) || !Files.isDirectory(scanDir)) {
                        continue;
                    }
                    scannedAnyDirectory = true;
                    try (Stream<Path> stream = this.clipsMode ? Files.walk(scanDir) : Files.list(scanDir)) {
                        stream.filter(Files::isRegularFile)
                                .filter(VideoCollectionScreen::isSupportedVideo)
                                .forEach(path -> {
                                    try {
                                        VideoMetadata metadata = VideoMetadata.readQuick(path);
                                        String clipEventType = classifyClipEvent(baseDir, path);
                                        synchronized (scannedVideos) {
                                            scannedVideos.add(metadata);
                                            if (this.clipsMode) {
                                                scannedClipEvents.put(path, clipEventType);
                                                scannedEventTypes.add(clipEventType);
                                            }
                                        }
                                    } catch (Throwable throwable) {
                                        RecordableMod.LOGGER.warn("Skipping unreadable recording entry {}", path, throwable);
                                    }
                                });
                    }
                }

                if (!scannedAnyDirectory) {
                    errorMessage = Component.translatable(this.clipsMode
                            ? "screen.recordable.video_collection.no_clips"
                            : "screen.recordable.video_collection.no_recordings");
                } else {
                    for (VideoMetadata m : scannedVideos) {
                        totalBytes += Math.max(0L, m.sizeBytes);
                    }

                    if (scannedVideos.isEmpty()) {
                        errorMessage = Component.translatable(this.clipsMode
                                ? "screen.recordable.video_collection.no_clips"
                                : "screen.recordable.video_collection.no_recordings");
                    }
                }
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to refresh video collection.", throwable);
                errorMessage = Component.translatable("screen.recordable.video_collection.refresh_failed");
                isError = true;
            }

            // Update UI on main thread
            final List<VideoMetadata> finalVideos = scannedVideos;
            final Map<Path, String> finalClipEvents = scannedClipEvents;
            final java.util.Set<String> finalEventTypes = scannedEventTypes;
            final long finalBytes = totalBytes;
            final Component finalMessage = errorMessage;
            final boolean finalIsError = isError;
            
            Minecraft.getInstance().execute(() -> {
                this.allVideos.clear();
                this.allVideos.addAll(finalVideos);
                this.clipEventByPath.clear();
                this.clipEventByPath.putAll(finalClipEvents);
                rebuildClipEventOptions(finalEventTypes);
                this.totalSizeBytes = finalBytes;
                this.statusMessage = finalMessage;
                this.statusIsError = finalIsError;
                
                sortAllVideos();
                applyFilter();
                startDurationProbe();
            });
        });
    }

    /**
     * Probes video durations in a single background thread, updating entries
     * as results come in. The UI will show "..." until each duration resolves.
     */
    private void startDurationProbe() {
        // Snapshot file paths that need probing
        List<Path> needsProbe = new ArrayList<>();
        for (VideoMetadata m : allVideos) {
            if (m.durationSeconds <= 0D && m.file != null) {
                needsProbe.add(m.file);
            }
        }
        if (needsProbe.isEmpty()) return;

        durationProbeRunning.set(true);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "recordable-duration-prober");
            t.setDaemon(true);
            return t;
        });
        durationProber = exec;

        exec.submit(() -> {
            for (Path path : needsProbe) {
                if (!durationProbeRunning.get()) break;
                try {
                    VideoMetadata probed = VideoMetadata.probeDurationFor(path);
                    if (probed != null && durationProbeRunning.get()) {
                        // Schedule replacement on render thread
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null) {
                            mc.execute(() -> replaceProbedEntry(path, probed));
                        }
                    }
                } catch (Throwable t) {
                    RecordableMod.LOGGER.debug("Background duration probe error for {}", path, t);
                }
            }
            durationProbeRunning.set(false);
        });
    }

    /** Replace an entry in the video lists after its duration was probed. */
    private void replaceProbedEntry(Path file, VideoMetadata probed) {
        replaceInList(allVideos, file, probed);
        replaceInList(filteredVideos, file, probed);
    }

    private static void replaceInList(List<VideoMetadata> list, Path file, VideoMetadata replacement) {
        for (int i = 0; i < list.size(); i++) {
            VideoMetadata existing = list.get(i);
            if (existing != null && existing.file != null && existing.file.equals(replacement.file)) {
                list.set(i, replacement);
                break;
            }
        }
    }

    private void applyFilter() {
        String query = this.searchField == null ? "" : this.searchField.getValue();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        this.filteredVideos.clear();
        if (normalized.isBlank()) {
            this.filteredVideos.addAll(this.allVideos);
        } else {
            for (VideoMetadata metadata : this.allVideos) {
                if (metadata == null) {
                    continue;
                }
                String haystack = (metadata.filename + " " + metadata.recordedAtDisplay).toLowerCase(Locale.ROOT);
                boolean queryMatch = haystack.contains(normalized);
                boolean eventMatch = !this.clipsMode || CLIP_EVENT_ALL.equals(this.selectedClipEvent)
                        || this.selectedClipEvent.equals(this.clipEventByPath.get(metadata.file));
                if (queryMatch && eventMatch) {
                    this.filteredVideos.add(metadata);
                }
            }
        }

        clampScroll();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int viewHeight = Math.max(0, this.listBottom - this.listTop);
        int maxScroll = Math.max(0, this.filteredVideos.size() * ENTRY_HEIGHT - viewHeight);
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int delta = (int) Math.round(verticalAmount * -20.0D);
        if (delta == 0) {
            delta = verticalAmount > 0 ? -20 : 20;
        }
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) {
            return true;
        }

        double mouseX = click.x();
        double mouseY = click.y();
        if (isOverScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        // Iterate top-most first so a drawer/flyout drawn last wins over anything beneath it.
        for (int i = this.actionZones.size() - 1; i >= 0; i--) {
            ActionZone zone = this.actionZones.get(i);
            if (zone.contains(mouseX, mouseY)) {
                zone.action.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.draggingScrollbar) {
            scrollToMouse(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    /** True if the cursor is over the scrollbar track (and there is something to scroll). */
    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int viewHeight = Math.max(0, this.listBottom - this.listTop);
        int contentHeight = this.filteredVideos.size() * ENTRY_HEIGHT;
        if (contentHeight <= viewHeight) {
            return false;
        }
        int scrollbarLeft = this.listRight - 6;
        int scrollbarRight = this.listRight;
        return mouseX >= scrollbarLeft && mouseX <= scrollbarRight
                && mouseY >= this.listTop && mouseY <= this.listBottom;
    }

    /** Map a vertical mouse position onto the scroll range so the thumb follows the cursor. */
    private void scrollToMouse(double mouseY) {
        int viewHeight = Math.max(1, this.listBottom - this.listTop);
        int contentHeight = this.filteredVideos.size() * ENTRY_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        if (maxScroll <= 0) {
            return;
        }
        int thumbHeight = Math.max(24, (int) (viewHeight * (viewHeight / (double) contentHeight)));
        int available = Math.max(1, viewHeight - thumbHeight);
        double ratio = (mouseY - this.listTop - thumbHeight / 2.0) / available;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        this.scrollOffset = (int) Math.round(ratio * maxScroll);
        clampScroll();
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (this.shareTarget != null && keyEvent.key() == 256) { // escape closes the drawer first, then the modal
            if (this.shareRetentionDrawerOpen) {
                this.shareRetentionDrawerOpen = false;
            } else {
                closeShareDialog();
            }
            return true;
        }
        if (this.searchField != null && this.searchField.isFocused()) {
            return super.keyPressed(keyEvent);
        }

        int keyCode = keyEvent.key();
        if (keyCode == 264) { // down
            this.scrollOffset += ENTRY_HEIGHT;
            clampScroll();
            return true;
        }
        if (keyCode == 265) { // up
            this.scrollOffset -= ENTRY_HEIGHT;
            clampScroll();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.extractMenuBackground(context);

        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();

        int panelLeft = this.listLeft - 8;
        int panelRight = this.listRight + 8;
        int panelTop = this.headerTop - 4;
        int panelBottom = this.height - 8;

        // Draw themed panel
        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(context, panelLeft, panelTop, panelRight, panelBottom);
        } else {
            ThemedPanel.drawPanel(context, panelLeft, panelTop, panelRight, panelBottom);
        }

        // Title with theme decoration
        if (preset == ThemePreset.VHS) {
            TypewriterText.renderFlickerText(context, this.font,
                    "▶ " + this.title.getString(),
                    this.width / 2 - this.font.width("▶ " + this.title.getString()) / 2,
                    panelTop + 6, colors.headerText);
        } else if (preset == ThemePreset.CINEMA) {
            context.centeredText(this.font,
                    "🎬 " + this.title.getString(),
                    this.width / 2, panelTop + 6, colors.headerText);
        } else {
            context.centeredText(this.font, this.title, this.width / 2, panelTop + 6, colors.headerText);
        }

        String summary = Component.translatable(
                "screen.recordable.video_collection.summary",
                Integer.toString(this.allVideos.size()),
                formatSizeMb(this.totalSizeBytes)
        ).getString();
        context.text(this.font,
                Component.literal(fitTextToWidth(summary, Math.max(32, this.listRight - this.listLeft - 10))),
                this.listLeft,
                panelTop + 6,
                colors.textMuted);

        // Render widgets (buttons, search field) now, BEFORE the themed list and entries are drawn.
        // The vanilla background is repainted at the start of the widget render pass, so it must run
        // here (not at the end). Running it late painted the background over every themed fill and
        // thumbnail texture, leaving only the batched text visible.
        super.extractRenderState(context, mouseX, mouseY, delta);

        // List area with themed borders
        context.fill(this.listLeft, this.listTop, this.listRight, this.listBottom, colors.sectionBackground);
        context.fill(this.listLeft, this.listTop, this.listRight, this.listTop + 1, colors.accent);
        context.fill(this.listLeft, this.listBottom - 1, this.listRight, this.listBottom, colors.panelBorder);

        // Film sprockets on list edges for Cinema theme
        if (preset == ThemePreset.CINEMA) {
            VhsEffectsRenderer.renderSprocketHoles(context, this.listLeft - 6, this.listTop, this.listBottom, colors.accent);
            VhsEffectsRenderer.renderSprocketHoles(context, this.listRight + 1, this.listTop, this.listBottom, colors.accent);
        }

        this.actionZones.clear();

        if (this.filteredVideos.isEmpty()) {
            Component noItemsText = Component.translatable("screen.recordable.video_collection.no_recordings");
            context.centeredText(this.font, noItemsText, this.width / 2,
                    this.listTop + Math.max(8, (this.listBottom - this.listTop) / 2 - 6), colors.textMuted);
            // Show reel loading animation when empty
            if (preset == ThemePreset.VHS || preset == ThemePreset.CINEMA) {
                ThemedPanel.drawReelLoading(context, this.width / 2, this.listTop + (this.listBottom - this.listTop) / 2 + 16, 12);
            }
        } else {
            renderVideoEntries(context, mouseX, mouseY);
        }

        if (this.statusMessage != null) {
            context.text(this.font,
                    Component.literal(fitTextToWidth(this.statusMessage.getString(), Math.max(32, this.listRight - this.listLeft - 10))),
                    this.listLeft,
                    this.height - 18,
                    this.statusIsError ? colors.textError : colors.textMuted);
        }

        // Draw the Share host-choice overlay on top of everything else (including widgets).
        // When open, it takes over click handling (only its buttons register action zones).
        if (this.shareTarget != null) {
            renderShareOverlay(context, mouseX, mouseY);
        }

    }

    /**
     * Modal overlay letting the user pick a host to upload the selected recording to.
     * Catbox is permanent (200 MB limit); Litterbox is temporary (1 GB limit, expires in 72h).
     */
    private void renderShareOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        ThemeColors tc = ThemeEngine.get().colors();

        // Raise this modal onto a fresh GUI stratum so it composites ABOVE the
        // batched text of the underlying list (filenames, Play/Folder/Delete...).
        // Without this the list text bleeds through the dim and panel.
        context.nextStratum();

        // Dim the whole screen behind the modal (E0 = ~88% opacity for strong visual separation).
        context.fill(0, 0, this.width, this.height, 0xE0000000);

        int panelWidth = Math.min(336, this.width - 20);
        int panelHeight = 210;
        int px = (this.width - panelWidth) / 2;
        int py = (this.height - panelHeight) / 2;

        // Panel background and border.
        context.fill(px, py, px + panelWidth, py + panelHeight, tc.panelBackground);
        context.fill(px, py, px + panelWidth, py + 1, tc.accent);
        context.fill(px, py + panelHeight - 1, px + panelWidth, py + panelHeight, tc.panelBorder);
        context.fill(px, py, px + 1, py + panelHeight, tc.panelBorder);
        context.fill(px + panelWidth - 1, py, px + panelWidth, py + panelHeight, tc.panelBorder);

        int centerX = px + panelWidth / 2;
        String fileName = this.shareTarget.filename == null
                ? Component.translatable("screen.recordable.video_collection.share").getString()
                : this.shareTarget.filename;
        context.centeredText(this.font,
                Component.translatable("screen.recordable.video_collection.share_title", ellipsize(fileName, 34)),
                centerX, py + 8, tc.headerText);
        context.centeredText(this.font,
                Component.translatable("screen.recordable.video_collection.share_prompt"),
                centerX, py + 22, tc.textSecondary);

        // Clear list/entry click zones so only the modal is interactive while it is open.
        this.actionZones.clear();

        int btnLeft = px + 12;
        int btnWidth = panelWidth - 24;

        // Keep the success state visible for 1 second before auto-closing the overlay.
        if (!this.sharing && this.shareOverlayUntilMs > 0L) {
            if (Util.getMillis() >= this.shareOverlayUntilMs) {
                closeShareDialog();
                return;
            }
            context.centeredText(this.font,
                    this.shareOverlayMessage == null
                            ? Component.translatable("screen.recordable.video_collection.share_copied")
                            : this.shareOverlayMessage,
                    centerX, py + panelHeight / 2 - 8, tc.textPrimary);
            context.text(this.font,
                    Component.translatable("screen.recordable.video_collection.share_note_line1"),
                    btnLeft + 6, py + panelHeight - 44, tc.textMuted);
            context.text(this.font,
                    Component.translatable("screen.recordable.video_collection.share_note_line2"),
                    btnLeft + 6, py + panelHeight - 31, tc.textMuted);
            return;
        }

        if (this.sharing) {
            // Upload in progress: show a live loading bar instead of the host buttons.
            int percent = Math.max(0, Math.min(100, VideoShareUploader.lastProgressPercent));
            long uploaded = VideoShareUploader.lastUploadedBytes;
            long total = VideoShareUploader.lastTotalBytes;

            context.centeredText(this.font,
                    Component.translatable("screen.recordable.video_collection.share_uploading",
                            Component.translatable("screen.recordable.video_collection.share").getString()),
                    centerX, py + panelHeight / 2 - 20, tc.textPrimary);

            int barWidth = btnWidth - 4;
            int barHeight = 12;
            int barLeft = centerX - barWidth / 2;
            int barTop = py + panelHeight / 2 - 4;

            context.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0x40000000);
            context.fill(barLeft, barTop, barLeft + barWidth, barTop + 1, tc.panelBorder);
            context.fill(barLeft, barTop + barHeight - 1, barLeft + barWidth, barTop + barHeight, tc.panelBorder);
            context.fill(barLeft, barTop, barLeft + 1, barTop + barHeight, tc.panelBorder);
            context.fill(barLeft + barWidth - 1, barTop, barLeft + barWidth, barTop + barHeight, tc.panelBorder);

            int fillWidth = (int) Math.round((barWidth - 2) * (percent / 100.0));
            if (fillWidth > 0) {
                context.fill(barLeft + 1, barTop + 1, barLeft + 1 + fillWidth, barTop + barHeight - 1, tc.accent);
            }

            String label = total > 0L
                    ? percent + "%  (" + formatSize(uploaded) + " / " + formatSize(total) + ")"
                    : percent + "%";
            context.centeredText(this.font, Component.literal(label),
                    centerX, barTop + barHeight + 6, tc.textSecondary);
            return;
        }

        // Record-able server option. Clicking it opens a drawer of retention choices
        // (7 / 30 / 60 days) to the right instead of uploading immediately.
        int recordableY = py + 40;
        drawActionButton(context, mouseX, mouseY, btnLeft, recordableY, btnWidth, 16,
                Component.translatable("screen.recordable.video_collection.share_recordable"),
                () -> this.shareRetentionDrawerOpen = !this.shareRetentionDrawerOpen);
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_recordable_desc1"),
                btnLeft + 2, recordableY + 20, tc.textSecondary);
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_recordable_desc2"),
                btnLeft + 2, recordableY + 32, tc.textSecondary);

        // Litterbox option.
        int litterY = py + 94;
        drawActionButton(context, mouseX, mouseY, btnLeft, litterY, btnWidth, 16,
                Component.translatable("screen.recordable.video_collection.share_litterbox"),
                () -> shareTo(this.shareTarget, VideoShareUploader.Host.LITTERBOX));
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_litterbox_desc1"),
                btnLeft + 2, litterY + 20, tc.textSecondary);
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_litterbox_desc2"),
                btnLeft + 2, litterY + 32, tc.textSecondary);

        // Public-upload disclosure and Cancel.
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_note_line1"),
                btnLeft + 6, py + panelHeight - 44, tc.textMuted);
        context.text(this.font,
                Component.translatable("screen.recordable.video_collection.share_note_line2"),
                btnLeft + 6, py + panelHeight - 31, tc.textMuted);
        drawActionButton(context, mouseX, mouseY, centerX - 30, py + panelHeight - 19, 60, 14,
                Component.translatable("screen.recordable.video_collection.share_cancel"), this::closeShareDialog);

        // Retention drawer: rendered last so it sits on top of everything else.
        if (this.shareRetentionDrawerOpen) {
            renderRetentionDrawer(context, mouseX, mouseY, px, panelWidth, recordableY, btnLeft, btnWidth);
        }
    }

    /**
     * Draws the 7 / 30 / 60 day retention drawer just to the right of the re.share-abl.ink
     * button. If there is not enough room on the right (small windows) it flips to the left
     * so it never runs off-screen. Selecting an option starts the upload with that retention.
     */
    private void renderRetentionDrawer(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                       int px, int panelWidth, int recordableY, int btnLeft, int btnWidth) {
        ThemeColors tc = ThemeEngine.get().colors();
        int drawerW = 66;
        int drawerBtnH = 16;
        int drawerGap = 3;
        int pad = 3;

        int buttonRight = btnLeft + btnWidth;
        int drawerX = buttonRight + 6;
        // Flip to the left of the button if the drawer would run past the screen edge.
        if (drawerX + drawerW + pad > this.width - 2) {
            drawerX = btnLeft - drawerW - 6;
        }
        // If it still does not fit (very narrow window), tuck it inside the right edge.
        if (drawerX < 2) {
            drawerX = Math.max(2, px + panelWidth - drawerW - 6);
        }

        int drawerTop = recordableY - pad;
        int drawerHeight = pad * 2 + drawerBtnH * 3 + drawerGap * 2;

        // Drawer container background and border so it reads as a flyout panel.
        context.fill(drawerX - pad, drawerTop, drawerX + drawerW + pad, drawerTop + drawerHeight, tc.panelBackground);
        context.fill(drawerX - pad, drawerTop, drawerX + drawerW + pad, drawerTop + 1, tc.accent);
        context.fill(drawerX - pad, drawerTop + drawerHeight - 1, drawerX + drawerW + pad, drawerTop + drawerHeight, tc.panelBorder);
        context.fill(drawerX - pad, drawerTop, drawerX - pad + 1, drawerTop + drawerHeight, tc.panelBorder);
        context.fill(drawerX + drawerW + pad - 1, drawerTop, drawerX + drawerW + pad, drawerTop + drawerHeight, tc.panelBorder);

        int[] days = VideoShareUploader.RETENTION_DAY_OPTIONS;
        for (int i = 0; i < days.length; i++) {
            final int retentionDays = days[i];
            int by = recordableY + i * (drawerBtnH + drawerGap);
            drawActionButton(context, mouseX, mouseY, drawerX, by, drawerW, drawerBtnH,
                    Component.literal(retentionDays + " days"),
                    () -> {
                        this.shareRetentionDrawerOpen = false;
                        shareTo(this.shareTarget, VideoShareUploader.Host.RECORDABLE, retentionDays);
                    });
        }
    }

    private void openShareDialog(VideoMetadata metadata) {
        if (metadata == null || metadata.file == null || this.sharing) {
            return;
        }
        this.shareTarget = metadata;
        this.shareRetentionDrawerOpen = false;
        this.shareOverlayMessage = null;
        this.shareOverlayUntilMs = 0L;
    }

    private void closeShareDialog() {
        if (this.sharing) {
            return;
        }
        this.shareTarget = null;
        this.shareRetentionDrawerOpen = false;
        this.shareOverlayMessage = null;
        this.shareOverlayUntilMs = 0L;
    }

    /** Kicks off a background upload of the recording to the chosen host. */
    private void shareTo(VideoMetadata metadata, VideoShareUploader.Host host) {
        shareTo(metadata, host, VideoShareUploader.DEFAULT_RETENTION_DAYS);
    }

    /** Kicks off a background upload of the recording to the chosen host with a retention choice. */
    private void shareTo(VideoMetadata metadata, VideoShareUploader.Host host, int retentionDays) {
        if (metadata == null || metadata.file == null || host == null || this.sharing) {
            return;
        }
        this.shareRetentionDrawerOpen = false;
        final Path file = metadata.file;
        this.sharing = true;
        this.shareOverlayMessage = null;
        this.shareOverlayUntilMs = 0L;
        setStatus(Component.translatable("screen.recordable.video_collection.share_uploading", host.displayName), false);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "recordable-share-upload");
            t.setDaemon(true);
            return t;
        });
        this.shareUploader = exec;
        exec.submit(() -> {
            boolean ok;
            String result;
            try {
                result = VideoShareUploader.upload(file, host, retentionDays);
                ok = result != null && result.startsWith("http");
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Share upload failed for {}", file, throwable);
                result = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
                ok = false;
            }
            final boolean fOk = ok;
            final String fResult = result;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> onShareComplete(fOk, fResult));
            }
        });
    }

    /** Runs on the render thread once an upload finishes: copies the link and updates status. */
    private void onShareComplete(boolean ok, String result) {
        this.sharing = false;
        cancelShareUpload();
        if (ok) {
            if (this.minecraft != null && this.minecraft.keyboardHandler != null) {
                try {
                    this.minecraft.keyboardHandler.setClipboard(result);
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.debug("Could not copy share link to clipboard.", throwable);
                }
            }
            this.shareOverlayMessage = Component.translatable("screen.recordable.video_collection.share_copied");
            this.shareOverlayUntilMs = Util.getMillis() + 1000L;
            setStatus(Component.translatable("screen.recordable.video_collection.share_copied"), false);
        } else {
            this.shareTarget = null;
            this.shareOverlayMessage = null;
            this.shareOverlayUntilMs = 0L;
            setStatus(Component.translatable("screen.recordable.video_collection.share_failed",
                    result == null ? "" : result), true);
        }
    }

    private void cancelShareUpload() {
        ExecutorService exec = this.shareUploader;
        if (exec != null) {
            exec.shutdown();
            this.shareUploader = null;
        }
    }

    private static String ellipsize(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, Math.max(0, max - 3)) + "..." : text;
    }

    private String fitTextToWidth(String text, int maxWidth) {
        if (text == null || maxWidth <= 8) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        int trimmedWidth = Math.max(1, maxWidth - this.font.width("..."));
        return this.font.plainSubstrByWidth(text, trimmedWidth) + "...";
    }

    private String classifyClipEvent(Path baseDir, Path filePath) {
        if (!this.clipsMode || baseDir == null || filePath == null) {
            return CLIP_EVENT_ALL;
        }
        try {
            Path clipsRoot = baseDir.resolve("clips");
            if (filePath.startsWith(clipsRoot)) {
                Path relative = clipsRoot.relativize(filePath);
                if (relative.getNameCount() >= 2) {
                    String segment = relative.getName(0).toString().trim();
                    if (!segment.isEmpty()) {
                        return segment.toLowerCase(Locale.ROOT);
                    }
                }
            }
            Path legacyRoot = baseDir.resolve("recording_auto_clips");
            if (filePath.startsWith(legacyRoot)) {
                return "legacy";
            }
        } catch (Throwable ignored) {
        }
        return "other";
    }

    private void rebuildClipEventOptions(java.util.Set<String> discoveredEventTypes) {
        this.clipEventOptions.clear();
        this.clipEventOptions.add(CLIP_EVENT_ALL);
        for (String knownType : DEFAULT_CLIP_EVENT_TYPES) {
            if (!this.clipEventOptions.contains(knownType)) {
                this.clipEventOptions.add(knownType);
            }
        }
        if (discoveredEventTypes != null && !discoveredEventTypes.isEmpty()) {
            List<String> sorted = new ArrayList<>();
            for (String value : discoveredEventTypes) {
                if (value == null || value.isBlank() || CLIP_EVENT_ALL.equals(value)) {
                    continue;
                }
                if (!this.clipEventOptions.contains(value)) {
                    sorted.add(value);
                }
            }
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            this.clipEventOptions.addAll(sorted);
        }
        syncSelectedClipEvent();
        updateClipEventButtonLabel();
    }

    private void syncSelectedClipEvent() {
        if (this.initialClipEvent != null && this.clipEventOptions.contains(this.initialClipEvent)) {
            this.selectedClipEvent = this.initialClipEvent;
            return;
        }
        if (this.selectedClipEvent == null || !this.clipEventOptions.contains(this.selectedClipEvent)) {
            this.selectedClipEvent = CLIP_EVENT_ALL;
        }
    }

    private void cycleClipEvent(boolean forward) {
        if (!this.clipsMode || this.clipEventOptions.isEmpty()) {
            return;
        }
        int index = this.clipEventOptions.indexOf(this.selectedClipEvent);
        if (index < 0) {
            index = 0;
        }
        int step = forward ? 1 : -1;
        int next = (index + step + this.clipEventOptions.size()) % this.clipEventOptions.size();
        this.selectedClipEvent = this.clipEventOptions.get(next);
        updateClipEventButtonLabel();
        applyFilter();
    }

    private void updateClipEventButtonLabel() {
        if (this.clipEventButton != null) {
            this.clipEventButton.setMessage(Component.literal(clipEventButtonLabel()));
        }
    }

    private String clipEventButtonLabel() {
        return "Event: " + clipEventDisplayName(this.selectedClipEvent);
    }

    private static String clipEventDisplayName(String eventType) {
        if (eventType == null || CLIP_EVENT_ALL.equals(eventType)) {
            return "All";
        }
        if ("legacy".equals(eventType)) {
            return "Legacy";
        }
        if ("other".equals(eventType)) {
            return "Other";
        }
        String normalized = eventType.trim();
        if (normalized.isEmpty()) {
            return "Other";
        }
        StringBuilder out = new StringBuilder(normalized.length());
        boolean upper = true;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '_' || ch == '-') {
                out.append(' ');
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(ch) : ch);
            upper = ch == ' ';
        }
        return out.toString();
    }

    private void renderVideoEntries(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int viewHeight = Math.max(1, this.listBottom - this.listTop);
        int firstIndex = Math.max(0, this.scrollOffset / ENTRY_HEIGHT);
        int lastIndexExclusive = Math.min(this.filteredVideos.size(), firstIndex + (viewHeight / ENTRY_HEIGHT) + 3);
        int y = this.listTop - (this.scrollOffset % ENTRY_HEIGHT);

        for (int index = firstIndex; index < lastIndexExclusive; index++) {
            VideoMetadata metadata = this.filteredVideos.get(index);
            int entryTop = y + (index - firstIndex) * ENTRY_HEIGHT;
            int entryBottom = entryTop + ENTRY_HEIGHT - 2;
            if (entryTop < this.listTop || entryBottom > this.listBottom) {
                continue;
            }

            renderEntry(context, metadata, entryTop, entryBottom, mouseX, mouseY);
        }

        renderScrollBar(context, viewHeight);
    }

    private void renderEntry(GuiGraphicsExtractor context, VideoMetadata metadata, int top, int bottom, int mouseX, int mouseY) {
        if (metadata == null) {
            return;
        }

        ThemeColors tc = ThemeEngine.get().colors();
        int accent = tc.accent;
        boolean hovered = mouseY >= top && mouseY <= bottom && mouseX >= this.listLeft && mouseX <= this.listRight;
        int background = hovered ? tc.panelBackground : ThemeEngine.lerpColor(tc.panelBackground, 0xFF000000, 0.3f);
        context.fill(this.listLeft + 2, top, this.listRight - 2, bottom, background);
        // Accent left-edge on hover
        if (hovered) {
            context.fill(this.listLeft + 2, top, this.listLeft + 4, bottom, accent);
        }

        int thumbLeft = this.listLeft + 6;
        int thumbTop = top + 5;
        int thumbWidth = 74;
        int thumbHeight = 40;

        context.fill(thumbLeft, thumbTop, thumbLeft + thumbWidth, thumbTop + thumbHeight, tc.panelBackground);
        context.fill(thumbLeft, thumbTop, thumbLeft + thumbWidth, thumbTop + 1, tc.panelBorder);
        context.fill(thumbLeft, thumbTop + thumbHeight - 1, thumbLeft + thumbWidth, thumbTop + thumbHeight, tc.panelBorder);

        boolean renderedThumb = drawThumbnail(context, metadata, thumbLeft + 1, thumbTop + 1, thumbWidth - 2, thumbHeight - 2);
        if (!renderedThumb) {
            context.fill(thumbLeft + 8, thumbTop + 7, thumbLeft + 66, thumbTop + 33, tc.sectionHover);
            context.centeredText(this.font, Component.literal("VIDEO"), thumbLeft + thumbWidth / 2, thumbTop + 15, accent);
        }

        boolean isProtected = StorageManager.isProtected(RecordableConfig.get(), metadata.filename);

        int buttonsRight = this.listRight - 6;
        int col3 = buttonsRight - BUTTON_WIDTH;
        int col2 = col3 - 5 - BUTTON_WIDTH;
        int col1 = col2 - 5 - BUTTON_WIDTH;

        int textX = thumbLeft + thumbWidth + 8;
        int textMaxWidth = Math.max(40, (col1 - 8) - textX);
        String displayName = (isProtected ? "🔒 " : "") + metadata.filename;
        context.text(this.font, Component.literal(fitTextToWidth(displayName, textMaxWidth)), textX, top + 4,
                isProtected ? tc.accent : tc.textPrimary);
        String sizeDuration = Component.translatable("screen.recordable.video_collection.meta.size_duration",
                metadata.sizeDisplay, metadata.durationDisplay).getString();
        context.text(this.font, Component.literal(fitTextToWidth(sizeDuration, textMaxWidth)), textX, top + 17, tc.textSecondary);
        String recordedAt = Component.translatable("screen.recordable.video_collection.meta.recorded_at",
                metadata.recordedAtDisplay).getString();
        context.text(this.font, Component.literal(fitTextToWidth(recordedAt, textMaxWidth)), textX, top + 29, tc.textSecondary);
        int row1 = top + 5;
        int row2 = top + 24;
        int row3 = top + 43;

        // Row 1: primary view actions next to the thumbnail.
        drawActionButton(context, mouseX, mouseY, col1, row1, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("screen.recordable.video_collection.play"), () -> playInGame(metadata.file));
        if (PlatformUtils.isAndroid()) {
            // The launcher sandbox cannot open a folder in an external app, so
            // show a greyed, non-clickable label. Recordings are auto-saved to
            // the gallery (Movies/Record-able) instead.
            drawDisabledActionButton(context, col2, row1, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.translatable("screen.recordable.video_collection.open_folder"));
        } else {
            drawActionButton(context, mouseX, mouseY, col2, row1, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.translatable("screen.recordable.video_collection.open_folder"), () -> openContainingFolder(metadata.file));
        }
        // Row 2: protection and delete.
        drawActionButton(context, mouseX, mouseY, col1, row2, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable(isProtected
                        ? "screen.recordable.video_collection.unprotect"
                        : "screen.recordable.video_collection.protect"),
                () -> toggleProtect(metadata));
        drawActionButton(context, mouseX, mouseY, col2, row2, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("screen.recordable.video_collection.delete"), () -> confirmDelete(metadata.file));
        // Row 3: copy path and share sit below the other actions to avoid horizontal clutter.
        drawActionButton(context, mouseX, mouseY, col1, row3, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("screen.recordable.video_collection.copy_path"), () -> copyPath(metadata.file));
        drawActionButton(context, mouseX, mouseY, col2, row3, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("screen.recordable.video_collection.share"), () -> openShareDialog(metadata));
    }

    /** Toggle the protected flag for a recording (protected files cannot be deleted). */
    private void toggleProtect(VideoMetadata metadata) {
        if (metadata == null || metadata.filename == null) {
            return;
        }
        try {
            StorageManager.toggleProtected(RecordableConfig.get(), metadata.filename);
            boolean nowProtected = StorageManager.isProtected(RecordableConfig.get(), metadata.filename);
            // Cancel any pending delete confirmation when protecting a file.
            if (nowProtected && metadata.file != null && metadata.file.equals(this.deleteConfirmPath)) {
                this.deleteConfirmPath = null;
                this.deleteConfirmUntil = 0L;
            }
            setStatus(Component.translatable(nowProtected
                    ? "screen.recordable.video_collection.protected"
                    : "screen.recordable.video_collection.unprotected", metadata.filename), false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to toggle protection for {}", metadata.filename, throwable);
        }
    }

    private boolean drawThumbnail(GuiGraphicsExtractor context, VideoMetadata metadata, int x, int y, int width, int height) {
        if (metadata.thumbnailPath == null || !Files.exists(metadata.thumbnailPath)) {
            return false;
        }

        ThumbnailTexture texture = thumbnailCache.get(metadata.thumbnailPath);
        if (texture == null) {
            texture = loadThumbnailTexture(metadata.thumbnailPath);
            if (texture != null) {
                thumbnailCache.put(metadata.thumbnailPath, texture);
            }
        }

        if (texture == null || texture.identifier == null) {
            return false;
        }

        try {
            context.blit(RenderPipelines.GUI_TEXTURED, texture.identifier, x, y, 0.0F, 0.0F, width, height, width, height);
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Failed to draw thumbnail texture for {}", metadata.thumbnailPath, throwable);
            return false;
        }
    }

    private ThumbnailTexture loadThumbnailTexture(Path thumbnailPath) {
        Minecraft client = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        if (client == null || client.getTextureManager() == null) {
            return null;
        }

        try {
            if (!Files.exists(thumbnailPath) || !Files.isReadable(thumbnailPath)) {
                RecordableMod.LOGGER.debug("Thumbnail path is missing/unreadable: {}", thumbnailPath);
                return null;
            }
            NativeImage image;
            try (java.io.InputStream stream = Files.newInputStream(thumbnailPath)) {
                image = NativeImage.read(stream);
            }
            DynamicTexture nativeTexture = new DynamicTexture(() -> "recordable-thumb", image);
            String idSuffix = Integer.toHexString(thumbnailPath.toAbsolutePath().toString().hashCode());
            Identifier id = dev.recordable.VersionHelper.id(RecordableMod.MOD_ID, "thumb/" + idSuffix);
            client.getTextureManager().register(id, nativeTexture);
            return new ThumbnailTexture(id, nativeTexture);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Failed to load thumbnail texture from {}", thumbnailPath, throwable);
            return null;
        }
    }

    private void clearThumbnails() {
        Minecraft client = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        for (ThumbnailTexture texture : this.thumbnailCache.values()) {
            if (texture == null) {
                continue;
            }
            try {
                if (client != null && client.getTextureManager() != null && texture.identifier != null) {
                    client.getTextureManager().release(texture.identifier);
                }
                if (texture.texture != null) {
                    texture.texture.close();
                }
            } catch (Throwable ignored) {
            }
        }
        this.thumbnailCache.clear();
    }

    private void drawActionButton(GuiGraphicsExtractor context,
                                  int mouseX,
                                  int mouseY,
                                  int x,
                                  int y,
                                  int width,
                                  int height,
                                  Component label,
                                  Runnable action) {
        ThemeColors tc = ThemeEngine.get().colors();
        int accent = tc.accent;
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        int fill = hovered ? tc.sectionHover : tc.sectionBackground;
        context.fill(x, y, x + width, y + height, fill);
        context.fill(x, y, x + width, y + 1, hovered ? accent : tc.panelBorder);
        context.fill(x, y + height - 1, x + width, y + height, tc.panelBorder);
        context.fill(x, y, x + 1, y + height, tc.panelBorder);
        context.fill(x + width - 1, y, x + width, y + height, tc.panelBorder);
        context.centeredText(this.font, label, x + width / 2, y + 3, hovered ? tc.textPrimary : tc.textSecondary);
        this.actionZones.add(new ActionZone(x, y, x + width, y + height, action));
    }

    // Draws a greyed-out, non-interactive label in place of an action button.
    // Used on Android for "Open Folder", which cannot work from the launcher
    // sandbox; no click zone is registered so the label does nothing.
    private void drawDisabledActionButton(GuiGraphicsExtractor context,
                                          int x,
                                          int y,
                                          int width,
                                          int height,
                                          Component label) {
        ThemeColors tc = ThemeEngine.get().colors();
        context.fill(x, y, x + width, y + height, tc.sectionBackground);
        context.fill(x, y, x + width, y + 1, tc.panelBorder);
        context.fill(x, y + height - 1, x + width, y + height, tc.panelBorder);
        context.fill(x, y, x + 1, y + height, tc.panelBorder);
        context.fill(x + width - 1, y, x + width, y + height, tc.panelBorder);
        context.centeredText(this.font, label, x + width / 2, y + 3, tc.textMuted);
    }

    private void renderScrollBar(GuiGraphicsExtractor context, int viewHeight) {
        int contentHeight = this.filteredVideos.size() * ENTRY_HEIGHT;
        if (contentHeight <= viewHeight) {
            return;
        }

        ThemeColors tc = ThemeEngine.get().colors();
        int scrollbarLeft = this.listRight - 6;
        int scrollbarRight = this.listRight - 2;
        context.fill(scrollbarLeft, this.listTop, scrollbarRight, this.listBottom, tc.sectionBackground);

        int thumbHeight = Math.max(24, (int) (viewHeight * (viewHeight / (double) contentHeight)));
        int maxScroll = contentHeight - viewHeight;
        int available = viewHeight - thumbHeight;
        int thumbTop = this.listTop + (int) ((this.scrollOffset / (double) maxScroll) * available);
        context.fill(scrollbarLeft, thumbTop, scrollbarRight, thumbTop + thumbHeight,
                this.draggingScrollbar ? tc.textPrimary : tc.accent);
    }

    private String categoryToggleLabel() {
        if (!this.clipsMode) return "Recordings";
        return this.hindsightCategoryMode ? "Hindsight" : "Clips";
    }

    private void cycleCategoryView(boolean forward) {
        if (this.minecraft == null) {
            return;
        }
        int current = !this.clipsMode ? 0 : (this.hindsightCategoryMode ? 2 : 1);
        int step = forward ? 1 : -1;
        int next = (current + step + 3) % 3;
        switch (next) {
            case 0 -> this.minecraft.setScreenAndShow(new VideoCollectionScreen(this.parent, false));
            case 1 -> this.minecraft.setScreenAndShow(new VideoCollectionScreen(this.parent, true));
            default -> this.minecraft.setScreenAndShow(new VideoCollectionScreen(this.parent, true, CLIP_EVENT_HINDSIGHT));
        }
    }

    private void openRecordingsFolder() {
        try {
            Path dir = RecordableConfig.get().getOutputDirectory();
            if (this.clipsMode) {
                dir = dir.resolve("clips");
                if (this.hindsightCategoryMode) {
                    dir = dir.resolve(CLIP_EVENT_HINDSIGHT);
                }
            }
            Files.createDirectories(dir);
            // Desktop only - the toolbar folder button is disabled on Android.
            net.minecraft.util.Util.getPlatform().openPath(dir);
            setStatus(Component.translatable("screen.recordable.video_collection.opened_folder"), false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to open recordings folder from collection screen.", throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.open_folder_failed"), true);
        }
    }

    private void openContainingFolder(Path file) {
        if (file == null) {
            return;
        }

        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("Missing parent directory");
            }
            Files.createDirectories(parent);
            // Desktop only - the per-video folder button is a disabled label on Android.
            net.minecraft.util.Util.getPlatform().openPath(parent);
            setStatus(Component.translatable("screen.recordable.video_collection.opened_folder"), false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to open folder for {}", file, throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.open_folder_failed"), true);
        }
    }

    private void playInGame(Path file) {
        if (file == null || this.minecraft == null) {
            return;
        }
        try {
            clearThumbnails();
            this.minecraft.setScreenAndShow(new VideoPlayerScreen(file, this));
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to open in-game video player for {}", file, throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.play_failed"), true);
        }
    }

    private void openFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            // Desktop only. In-game playback (Play button) is used on Android.
            net.minecraft.util.Util.getPlatform().openPath(file);
            setStatus(Component.translatable("screen.recordable.video_collection.playing", file.getFileName()), false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to open video file {}", file, throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.play_failed"), true);
        }
    }

    private void copyPath(Path file) {
        if (file == null || this.minecraft == null || this.minecraft == null) {
            return;
        }
        try {
            this.minecraft.keyboardHandler.setClipboard(file.toAbsolutePath().toString());
            setStatus(Component.translatable("screen.recordable.video_collection.copied_path"), false);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to copy file path for {}", file, throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.copy_failed"), true);
        }
    }

    private void confirmDelete(Path file) {
        if (file == null) {
            return;
        }

        // Protected recordings cannot be deleted until they are unlocked.
        if (StorageManager.isProtected(RecordableConfig.get(), file.getFileName().toString())) {
            setStatus(Component.translatable("screen.recordable.video_collection.delete_protected", file.getFileName()), true);
            return;
        }

        long now = System.currentTimeMillis();
        if (!file.equals(this.deleteConfirmPath) || now > this.deleteConfirmUntil) {
            this.deleteConfirmPath = file;
            this.deleteConfirmUntil = now + DELETE_CONFIRM_MS;
            setStatus(Component.translatable("screen.recordable.video_collection.delete_confirm", file.getFileName()), true);
            return;
        }

        this.deleteConfirmPath = null;
        this.deleteConfirmUntil = 0L;

        try {
            Files.deleteIfExists(file);
            setStatus(Component.translatable("screen.recordable.video_collection.deleted", file.getFileName()), false);
            refreshVideos();
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to delete recording {}", file, throwable);
            setStatus(Component.translatable("screen.recordable.video_collection.delete_failed", file.getFileName()), true);
        }
    }

    private void clampScroll() {
        int viewHeight = Math.max(0, this.listBottom - this.listTop);
        int maxScroll = Math.max(0, this.filteredVideos.size() * ENTRY_HEIGHT - viewHeight);
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
    }

    private void setStatus(Component text, boolean isError) {
        this.statusMessage = text;
        this.statusIsError = isError;
    }

    private static String formatSizeMb(long bytes) {
        return String.format(Locale.ROOT, "%.2f MB", Math.max(0L, bytes) / (1024.0D * 1024.0D));
    }

    /** Compact size label that switches to GB for large files, used by the share loading bar. */
    private static String formatSize(long bytes) {
        double mb = Math.max(0L, bytes) / (1024.0D * 1024.0D);
        if (mb >= 1024.0D) {
            return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private static boolean isSupportedVideo(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mkv");
    }

    private record ActionZone(int x1, int y1, int x2, int y2, Runnable action) {
        private boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private record ThumbnailTexture(Identifier identifier, DynamicTexture texture) {
    }
}
