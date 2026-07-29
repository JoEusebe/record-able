package dev.recordable;

/**
 * Legacy stub - the overlay system has moved to on-screen HUD rendering
 * via {@link RecordingOverlay} (DrawContext / HudRenderCallback).
 *
 * <p>This class is retained only so that old config files referencing
 * {@code VideoOverlayRenderer.OverlayStyle} still deserialize without
 * crashing. No frame compositing is performed.</p>
 */
public final class VideoOverlayRenderer {

    /** Legacy enum kept for backward-compatible config deserialization. */
    public enum OverlayStyle {
        NONE, CLASSIC, VHS_CLASSIC, VHS_FULL, SPEEDRUNNER
    }

    private VideoOverlayRenderer() { }
}
