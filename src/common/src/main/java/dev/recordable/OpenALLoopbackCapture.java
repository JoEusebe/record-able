package dev.recordable;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures Minecraft's audio output by hooking into OpenAL via the
 * {@code ALC_SOFT_loopback} extension.
 *
 * <p>When a loopback device is used instead of a real audio device,
 * OpenAL renders all mixed audio into a buffer that we control.
 * We then simultaneously:</p>
 * <ol>
 *   <li>Play the audio through Java Sound ({@link SourceDataLine}) so the user hears it</li>
 *   <li>Optionally write the raw PCM to a recording stream</li>
 * </ol>
 *
 * <p>This gives us perfect, full-volume game audio capture with zero
 * dependency on system loopback devices like Stereo Mix.</p>
 */
public final class OpenALLoopbackCapture {

    /** Default sample rate matching Minecraft's OpenAL output. */
    public static final int SAMPLE_RATE = 48000;
    /** Stereo output. */
    public static final int CHANNELS = 2;
    /** 16-bit signed PCM. */
    public static final int BITS_PER_SAMPLE = 16;

    /** Render interval in milliseconds. ~10ms = low latency. */
    private static final int RENDER_INTERVAL_MS = 10;
    /** Samples per render call (per channel). At 48kHz, 10ms = 480 samples. */
    private static final int SAMPLES_PER_RENDER = SAMPLE_RATE * RENDER_INTERVAL_MS / 1000;
    /** Byte size per render: samples * channels * bytesPerSample */
    private static final int BYTES_PER_RENDER = SAMPLES_PER_RENDER * CHANNELS * (BITS_PER_SAMPLE / 8);

    private static volatile OpenALLoopbackCapture instance;

    private volatile long loopbackDevice;
    private volatile boolean running;
    private Thread renderThread;
    private SourceDataLine speakerLine;
    private Object androidAudioTrack;
    private java.lang.reflect.Method androidAudioTrackWriteMethod;
    private java.lang.reflect.Method androidAudioTrackStopMethod;
    private java.lang.reflect.Method androidAudioTrackReleaseMethod;
    private int androidPlaybackSampleRate = SAMPLE_RATE;
    private volatile boolean androidAudioTrackUnsupported;

    /** Stream to write PCM data to when recording. Null when not recording. */
    private final AtomicReference<OutputStream> recordingStream = new AtomicReference<>(null);

    /** Number of bytes of PCM that correspond to one millisecond of audio. */
    private static final double BYTES_PER_MS = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8) / 1000.0;

    /**
     * Rolling PCM buffer for auto-clip / kill-montage audio. Only populated while
     * {@link #rollingEnabled} is true (i.e. during a combat / montage window), so it costs
     * nothing during normal play. Each chunk is one render's worth of PCM tagged with the
     * wall-clock time it was produced, which lets {@link #extractAudio} pull the exact audio
     * segment matching the buffered video frames.
     */
    private final Deque<TimedAudio> rollingAudio = new ArrayDeque<>();
    private volatile boolean rollingEnabled = false;
    private volatile long rollingRetentionMs = 0L;
    private record TimedAudio(byte[] pcm, long tsMs) {}

    private OpenALLoopbackCapture() {}

    public static OpenALLoopbackCapture getInstance() {
        if (instance == null) {
            synchronized (OpenALLoopbackCapture.class) {
                if (instance == null) {
                    instance = new OpenALLoopbackCapture();
                }
            }
        }
        return instance;
    }

    /**
     * Checks if the {@code ALC_SOFT_loopback} extension is available.
     * Must be called after OpenAL is minimally initialized (device pointer 0 = null device check).
     */
    public static boolean isLoopbackSupported() {
        try {
            return ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback");
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("ALC_SOFT_loopback extension check failed", t);
            return false;
        }
    }

    /**
     * Opens a loopback device via {@code alcLoopbackOpenDeviceSOFT}.
     * This replaces the normal {@code alcOpenDevice} call in SoundEngine.
     *
     * @return the loopback device pointer, or 0 on failure
     */
    public long openLoopbackDevice() {
        try {
            long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (device == 0L) {
                RecordableMod.LOGGER.error("alcLoopbackOpenDeviceSOFT returned 0 (failed)");
                return 0L;
            }

            boolean supported = SOFTLoopback.alcIsRenderFormatSupportedSOFT(
                    device,
                    SAMPLE_RATE,
                    SOFTLoopback.ALC_STEREO_SOFT,
                    SOFTLoopback.ALC_SHORT_SOFT
            );
            if (!supported) {
                RecordableMod.LOGGER.error("Loopback format not supported (48kHz stereo 16-bit)");
                ALC10.alcCloseDevice(device);
                return 0L;
            }

            this.loopbackDevice = device;
            RecordableMod.LOGGER.info("OpenAL loopback device opened successfully: {}", device);
            return device;
        } catch (Throwable t) {
            RecordableMod.LOGGER.error("Failed to open loopback device", t);
            return 0L;
        }
    }

    /**
     * Returns the OpenAL context attributes needed for a loopback device.
     * These must be passed to {@code alcCreateContext} instead of the normal attributes.
     */
    public int[] getContextAttributes() {
        return new int[]{
                SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
                ALC10.ALC_FREQUENCY, SAMPLE_RATE,
                0 // terminator
        };
    }

    /**
     * Starts the render thread that pulls audio from the loopback device
     * and plays it through Java Sound.
     */
    public void startRenderThread() {
        if (running) {
            return;
        }
        if (loopbackDevice == 0L) {
            RecordableMod.LOGGER.warn("Cannot start render thread: no loopback device");
            return;
        }

        running = true;
        renderThread = new Thread(this::renderLoop, "Record-able Audio Render");
        renderThread.setDaemon(true);
        renderThread.setPriority(Thread.MAX_PRIORITY - 1);
        renderThread.start();
        RecordableMod.LOGGER.info("Loopback render thread started");
    }

    /**
     * Stops the render thread and closes the speaker line.
     */
    public void stopRenderThread() {
        running = false;
        if (renderThread != null) {
            try {
                renderThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }
        closeSpeakerLine();
        RecordableMod.LOGGER.info("Loopback render thread stopped");
    }

    /**
     * Sets the output stream for recording. Pass null to stop recording.
     */
    public void setRecordingStream(OutputStream stream) {
        recordingStream.set(stream);
    }

    // ------------------------------------------------------------------
    // Rolling auto-clip / kill-montage audio buffer
    // ------------------------------------------------------------------

    /**
     * Enables (or refreshes) the rolling PCM buffer used to attach audio to auto-clip /
     * kill-montage clips. Safe to call repeatedly; the retention window is updated each time.
     * Capturing only happens while the loopback render thread is running, so this is a no-op
     * cost when audio is unavailable.
     *
     * @param retentionMs how many milliseconds of recent audio to retain (clamped 1s..30s)
     */
    public void enableRollingBuffer(long retentionMs) {
        long clamped = Math.max(1000L, Math.min(30_000L, retentionMs));
        synchronized (rollingAudio) {
            this.rollingRetentionMs = clamped;
            this.rollingEnabled = true;
        }
    }

    /** Disables the rolling buffer and frees its memory. */
    public void disableRollingBuffer() {
        synchronized (rollingAudio) {
            this.rollingEnabled = false;
            this.rollingAudio.clear();
        }
    }

    /** True if the rolling buffer is currently collecting audio. */
    public boolean isRollingBufferEnabled() {
        return rollingEnabled;
    }

    private void appendRollingAudio(byte[] audioBytes) {
        long now = System.currentTimeMillis();
        synchronized (rollingAudio) {
            if (!rollingEnabled) return;
            byte[] copy = audioBytes.clone();
            rollingAudio.addLast(new TimedAudio(copy, now));
            long cutoff = now - rollingRetentionMs;
            while (!rollingAudio.isEmpty()) {
                TimedAudio oldest = rollingAudio.peekFirst();
                if (oldest != null && oldest.tsMs() < cutoff) {
                    rollingAudio.pollFirst();
                } else {
                    break;
                }
            }
        }
    }

    /**
     * Extracts a contiguous block of signed 16-bit little-endian stereo PCM covering the
     * wall-clock window {@code [startMs, endMs]} from the rolling buffer.
     *
     * @return the PCM bytes for the window, or {@code null} if the buffer is empty / disabled
     */
    public byte[] extractAudio(long startMs, long endMs) {
        synchronized (rollingAudio) {
            if (rollingAudio.isEmpty() || endMs <= startMs) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(1024, (int) ((endMs - startMs) * BYTES_PER_MS)));
            for (TimedAudio chunk : rollingAudio) {
                if (chunk.tsMs() >= startMs && chunk.tsMs() <= endMs) {
                    out.write(chunk.pcm(), 0, chunk.pcm().length);
                }
            }
            byte[] result = out.toByteArray();
            return result.length > 0 ? result : null;
        }
    }

    /**
     * Returns true if a loopback device is active and the render thread is running.
     */
    public boolean isActive() {
        return running && loopbackDevice != 0L;
    }

    /**
     * Returns true if loopback is available and was successfully initialized.
     */
    public boolean hasLoopbackDevice() {
        return loopbackDevice != 0L;
    }

    private void renderLoop() {
        RecordableMod.LOGGER.info("Render loop starting: {}Hz {}ch {}bit, {} samples/render, {} bytes/render",
                SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE, SAMPLES_PER_RENDER, BYTES_PER_RENDER);

        ByteBuffer renderBuffer = ByteBuffer.allocateDirect(BYTES_PER_RENDER);
        renderBuffer.order(ByteOrder.nativeOrder());

        byte[] audioBytes = new byte[BYTES_PER_RENDER];

        if (PlatformUtils.isAndroid()) {
            if (!androidAudioTrackUnsupported && !openAndroidAudioTrack()) {
                RecordableMod.LOGGER.error("Failed to open Android AudioTrack, audio will be silent");
            }
        } else if (!openSpeakerLine()) {
            RecordableMod.LOGGER.error("Failed to open speaker line, audio will be silent");
        }

        long startNanos = System.nanoTime();
        long samplesRendered = 0L;

        while (running) {
            try {
                long device = this.loopbackDevice;
                if (device == 0L) {
                    Thread.sleep(50);
                    startNanos = System.nanoTime();
                    samplesRendered = 0L;
                    continue;
                }

                long expectedNanos = startNanos + (samplesRendered * 1_000_000_000L / SAMPLE_RATE);
                long nowNanos = System.nanoTime();
                long waitNanos = expectedNanos - nowNanos;

                if (waitNanos > 1_000_000L) { // > 1ms ahead
                    Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                } else if (waitNanos < -500_000_000L) {
                    startNanos = System.nanoTime();
                    samplesRendered = 0L;
                }

                renderBuffer.clear();
                SOFTLoopback.alcRenderSamplesSOFT(device, renderBuffer, SAMPLES_PER_RENDER);
                samplesRendered += SAMPLES_PER_RENDER;

                renderBuffer.rewind();
                renderBuffer.get(audioBytes);

                // Feed the rolling auto-clip/montage buffer (cheap copy, only while enabled).
                if (rollingEnabled) {
                    appendRollingAudio(audioBytes);
                }

                // FIX: Write to recording stream FIRST (higher priority).
                // Previously, speakerLine.write() could block when its buffer
                // was full (only 20ms buffer), which would also delay/block
                // the recording stream write, causing audio gaps in recordings.
                OutputStream stream = recordingStream.get();
                if (stream != null) {
                    try {
                        stream.write(audioBytes);
                    } catch (Throwable t) {
                        RecordableMod.LOGGER.debug("Recording stream write failed", t);
                    }
                }

                // Speaker playback second (can block without affecting recording)
                if (speakerLine != null && speakerLine.isOpen()) {
                    speakerLine.write(audioBytes, 0, audioBytes.length);
                } else if (androidAudioTrack != null && androidAudioTrackWriteMethod != null) {
                    try {
                        androidAudioTrackWriteMethod.invoke(androidAudioTrack, audioBytes, 0, audioBytes.length);
                    } catch (Throwable t) {
                        RecordableMod.LOGGER.debug("Android AudioTrack write failed", t);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("Render loop error", t);
                try { Thread.sleep(50); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        RecordableMod.LOGGER.info("Render loop exited");
    }

    private boolean openSpeakerLine() {
        try {
            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    BITS_PER_SAMPLE,
                    CHANNELS,
                    CHANNELS * (BITS_PER_SAMPLE / 8), // frame size
                    SAMPLE_RATE,
                    false // little-endian
            );

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                RecordableMod.LOGGER.warn("Java Sound does not support format: {}", format);
                return false;
            }

            speakerLine = (SourceDataLine) AudioSystem.getLine(info);
            // FIX: Increased buffer from 2x (20ms) to 8x (80ms) to reduce
            // frequency of blocking writes that could stall recording.
            int bufferSize = BYTES_PER_RENDER * 8;
            speakerLine.open(format, bufferSize);
            speakerLine.start();
            RecordableMod.LOGGER.info("Java Sound speaker line opened: {}", format);
            return true;
        } catch (Throwable t) {
            RecordableMod.LOGGER.error("Failed to open Java Sound speaker line", t);
            speakerLine = null;
            return false;
        }
    }

    private boolean openAndroidAudioTrack() {
        closeAndroidAudioTrack();
        try {
            Class<?> audioTrackClass = Class.forName("android.media.AudioTrack");
            Class<?> audioFormatClass = Class.forName("android.media.AudioFormat");
            Class<?> audioManagerClass = Class.forName("android.media.AudioManager");

            int streamMusic = audioManagerClass.getField("STREAM_MUSIC").getInt(null);
            int encodingPcm16bit = audioFormatClass.getField("ENCODING_PCM_16BIT").getInt(null);
            int channelOutStereo = audioFormatClass.getField("CHANNEL_OUT_STEREO").getInt(null);

            int sampleRate = SAMPLE_RATE;
            try {
                java.lang.reflect.Method getNativeOutputSampleRate = audioTrackClass.getMethod(
                        "getNativeOutputSampleRate", int.class);
                Object nativeRate = getNativeOutputSampleRate.invoke(null, streamMusic);
                if (nativeRate instanceof Integer) {
                    int parsed = (Integer) nativeRate;
                    if (parsed > 0) {
                        sampleRate = parsed;
                    }
                }
            } catch (Throwable ignored) {
                // Older Android versions do not expose this helper.
            }

            java.lang.reflect.Method getMinBufferSize = audioTrackClass.getMethod(
                    "getMinBufferSize", int.class, int.class, int.class);
            int minBufferSize = (int) getMinBufferSize.invoke(null, sampleRate, channelOutStereo, encodingPcm16bit);
            int bufferSize = Math.max(BYTES_PER_RENDER * 8, minBufferSize > 0 ? Math.max(minBufferSize * 2, BYTES_PER_RENDER * 8) : BYTES_PER_RENDER * 8);

            java.lang.reflect.Constructor<?> ctor = audioTrackClass.getConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            Object track = ctor.newInstance(streamMusic, sampleRate, channelOutStereo, encodingPcm16bit, bufferSize, 1);

            androidAudioTrackWriteMethod = audioTrackClass.getMethod("write", byte[].class, int.class, int.class);
            androidAudioTrackStopMethod = audioTrackClass.getMethod("stop");
            androidAudioTrackReleaseMethod = audioTrackClass.getMethod("release");
            java.lang.reflect.Method setStereoVolume = audioTrackClass.getMethod("setStereoVolume", float.class, float.class);
            setStereoVolume.invoke(track, 1.0f, 1.0f);
            audioTrackClass.getMethod("play").invoke(track);

            androidAudioTrack = track;
            androidPlaybackSampleRate = sampleRate;
            RecordableMod.LOGGER.info("Android AudioTrack opened: {}Hz stereo, buffer={} bytes", sampleRate, bufferSize);
            return true;
        } catch (ClassNotFoundException e) {
            androidAudioTrackUnsupported = true;
            RecordableMod.LOGGER.info("Android AudioTrack API is unavailable in this runtime, skipping local playback");
            closeAndroidAudioTrack();
            return false;
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to open Android AudioTrack with sampleRate={} (fallback to silent playback)", SAMPLE_RATE, t);
            closeAndroidAudioTrack();
            return false;
        }
    }

    private void closeSpeakerLine() {
        if (speakerLine != null) {
            try {
                speakerLine.stop();
                speakerLine.close();
            } catch (Throwable ignored) {}
            speakerLine = null;
        }
        closeAndroidAudioTrack();
    }

    private void closeAndroidAudioTrack() {
        if (androidAudioTrack != null) {
            try {
                if (androidAudioTrackStopMethod != null) {
                    androidAudioTrackStopMethod.invoke(androidAudioTrack);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (androidAudioTrackReleaseMethod != null) {
                    androidAudioTrackReleaseMethod.invoke(androidAudioTrack);
                }
            } catch (Throwable ignored) {
            }
            androidAudioTrack = null;
            androidAudioTrackWriteMethod = null;
            androidAudioTrackStopMethod = null;
            androidAudioTrackReleaseMethod = null;
        }
    }

    /**
     * Shuts down the entire loopback capture system.
     * Called when the game closes.
     */
    public void shutdown() {
        stopRenderThread();
        disableRollingBuffer();
        recordingStream.set(null);
        loopbackDevice = 0L;
        instance = null;
    }
}
