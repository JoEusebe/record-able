package dev.recordable;

/**
 * Completely disabled WASAPI capture stub.
 *
 * <p>This class intentionally contains no native/JNA logic so native audio
 * code paths can never execute.</p>
 */
public final class WASAPIAudioCapture {

    public static boolean isAvailable() {
        return false;
    }

    public boolean startCapture() {
        return false;
    }

    public void stopCapture() {
    }
}
