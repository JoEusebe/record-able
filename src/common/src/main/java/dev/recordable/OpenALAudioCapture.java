package dev.recordable;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Captures PCM audio from OpenAL using LWJGL capture APIs.
 *
 * <p>Output format is signed 16-bit little-endian PCM.</p>
 */
public final class OpenALAudioCapture {
    private static final int DEFAULT_QUEUE_CAPACITY = 120;

    private final int sampleRate;
    private final int channelCount;
    private final int bitsPerSample;
    private final int samplesPerRead;
    private final int openAlFormat;
    private final BlockingQueue<byte[]> audioQueue;

    private long captureDevice;
    private Thread captureThread;
    private volatile boolean capturing;

    public OpenALAudioCapture(int sampleRate, int channelCount) {
        this(sampleRate, channelCount, 16, Math.max(256, sampleRate / 10), DEFAULT_QUEUE_CAPACITY);
    }

    public OpenALAudioCapture(int sampleRate, int channelCount, int bitsPerSample, int samplesPerRead, int queueCapacity) {
        this.sampleRate = sampleRate <= 0 ? 48000 : sampleRate;
        this.channelCount = channelCount == 1 ? 1 : 2;
        this.bitsPerSample = bitsPerSample <= 0 ? 16 : bitsPerSample;
        this.samplesPerRead = Math.max(128, samplesPerRead);
        this.openAlFormat = this.channelCount == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        this.audioQueue = new LinkedBlockingQueue<>(Math.max(16, queueCapacity));
    }

    public boolean start() {
        if (capturing) {
            return true;
        }

        try {
            RecordableMod.LOGGER.info("Starting OpenAL audio capture: sampleRate={}Hz channels={} bits={} samplesPerRead={}",
                    sampleRate,
                    channelCount,
                    bitsPerSample,
                    samplesPerRead);

            captureDevice = ALC11.alcCaptureOpenDevice(
                    (ByteBuffer) null,
                    sampleRate,
                    openAlFormat,
                    sampleRate * 2
            );
            if (captureDevice == 0L) {
                RecordableMod.LOGGER.error("OpenAL capture device could not be opened.");
                return false;
            }

            ALC11.alcCaptureStart(captureDevice);
            capturing = true;

            captureThread = new Thread(this::captureLoop, "Record-able OpenAL Capture");
            captureThread.setDaemon(true);
            captureThread.start();
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.error("Failed to start OpenAL audio capture", throwable);
            stop();
            return false;
        }
    }

    private void captureLoop() {
        ShortBuffer sampleBuffer = BufferUtils.createShortBuffer(samplesPerRead * channelCount);

        while (capturing) {
            try {
                int available = ALC10.alcGetInteger(captureDevice, ALC11.ALC_CAPTURE_SAMPLES);
                if (available >= samplesPerRead) {
                    sampleBuffer.clear();
                    ALC11.alcCaptureSamples(captureDevice, sampleBuffer, samplesPerRead);

                    byte[] audioData = new byte[samplesPerRead * channelCount * 2];
                    sampleBuffer.flip();
                    for (int byteIndex = 0; byteIndex < audioData.length; byteIndex += 2) {
                        short sample = sampleBuffer.get();
                        audioData[byteIndex] = (byte) (sample & 0xFF);
                        audioData[byteIndex + 1] = (byte) ((sample >>> 8) & 0xFF);
                    }

                    if (!audioQueue.offer(audioData)) {
                        audioQueue.poll();
                        audioQueue.offer(audioData);
                    }
                } else {
                    Thread.sleep(5L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("OpenAL capture loop error", throwable);
                break;
            }
        }

        RecordableMod.LOGGER.info("OpenAL capture loop stopped.");
    }

    public byte[] getNextAudioFrame() {
        return audioQueue.poll();
    }

    public byte[] getNextAudioFrame(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return audioQueue.poll();
        }
        return audioQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int getQueueSize() {
        return audioQueue.size();
    }

    public boolean isCapturing() {
        return capturing;
    }

    public void stop() {
        capturing = false;

        if (captureThread != null) {
            try {
                captureThread.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (captureDevice != 0L) {
            try {
                ALC11.alcCaptureStop(captureDevice);
            } catch (Throwable ignored) {
            }
            try {
                ALC11.alcCaptureCloseDevice(captureDevice);
            } catch (Throwable ignored) {
            }
            captureDevice = 0L;
        }
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channelCount;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }
}
