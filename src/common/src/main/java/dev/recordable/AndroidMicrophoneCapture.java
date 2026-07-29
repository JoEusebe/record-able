package dev.recordable;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android microphone capture implemented via reflection against AudioRecord.
 *
 * <p>This provides a portable fallback for Android runtimes that do not expose
 * Java Sound or OpenAL microphone capture, and writes a PCM WAV file that the
 * existing muxing pipeline can already consume.</p>
 */
public final class AndroidMicrophoneCapture {
    private final Path outputFile;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread captureThread;
    private volatile Object audioRecord;
    private volatile Throwable lastError;
    private volatile int minBufferSize = 0;

    private AndroidMicrophoneCapture(Path outputFile, int sampleRate, int channels) {
        this.outputFile = outputFile;
        this.sampleRate = sampleRate > 0 ? sampleRate : 48000;
        this.channels = channels > 1 ? 2 : 1;
        this.bitsPerSample = 16;
    }

    public static boolean isSupported() {
        if (!PlatformUtils.isAndroid()) {
            return false;
        }
        try {
            Class.forName("android.media.AudioRecord");
            Class.forName("android.media.AudioFormat");
            Class.forName("android.media.MediaRecorder");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static AndroidMicrophoneCapture start(Path outputFile, int sampleRate, int channels) {
        if (!PlatformUtils.isAndroid() || !isSupported()) {
            return null;
        }
        AndroidMicrophoneCapture capture = new AndroidMicrophoneCapture(outputFile, sampleRate, channels);
        if (capture.start()) {
            return capture;
        }
        return null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public Throwable getLastError() {
        return lastError;
    }

    public boolean start() {
        if (running.get()) {
            return true;
        }

        try {
            Files.createDirectories(outputFile.getParent());

            Class<?> mediaRecorderClass = Class.forName("android.media.MediaRecorder");
            Class<?> audioSourceClass = Class.forName("android.media.MediaRecorder$AudioSource");
            Class<?> audioFormatClass = Class.forName("android.media.AudioFormat");
            Class<?> audioRecordClass = Class.forName("android.media.AudioRecord");

            int micSource = audioSourceClass.getField("MIC").getInt(null);

            int channelConfig = channels == 1
                    ? audioFormatClass.getField("CHANNEL_IN_MONO").getInt(null)
                    : audioFormatClass.getField("CHANNEL_IN_STEREO").getInt(null);
            int encoding = audioFormatClass.getField("ENCODING_PCM_16BIT").getInt(null);

            Method getMinBufferSize = audioRecordClass.getMethod("getMinBufferSize", int.class, int.class, int.class);
            minBufferSize = (Integer) getMinBufferSize.invoke(null, sampleRate, channelConfig, encoding);
            if (minBufferSize <= 0) {
                throw new IllegalStateException("AudioRecord reported an invalid minimum buffer size: " + minBufferSize);
            }

            int bufferSizeBytes = Math.max(minBufferSize * 2, 16 * 1024);
            Constructor<?> ctor = audioRecordClass.getConstructor(int.class, int.class, int.class, int.class, int.class);
            audioRecord = ctor.newInstance(micSource, sampleRate, channelConfig, encoding, bufferSizeBytes);

            int state = (Integer) audioRecordClass.getMethod("getState").invoke(audioRecord);
            if (state != 1) {
                throw new IllegalStateException("AudioRecord did not initialize successfully (state=" + state + ")");
            }

            audioRecordClass.getMethod("startRecording").invoke(audioRecord);
            running.set(true);

            captureThread = new Thread(this::captureLoop, "Record-able Android Mic Capture");
            captureThread.setDaemon(true);
            captureThread.start();
            return true;
        } catch (Throwable throwable) {
            lastError = throwable;
            RecordableMod.LOGGER.warn("Android microphone capture failed to start", throwable);
            return false;
        }
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        Object recorder = audioRecord;
        if (recorder != null) {
            try {
                recorder.getClass().getMethod("stop").invoke(recorder);
            } catch (Throwable ignored) {
            }
            try {
                recorder.getClass().getMethod("release").invoke(recorder);
            } catch (Throwable ignored) {
            }
            audioRecord = null;
        }

        Thread thread = captureThread;
        if (thread != null) {
            try {
                thread.join(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
    }

    private void captureLoop() {
        Class<?> audioRecordClass;
        Method readMethod;
        try {
            audioRecordClass = Class.forName("android.media.AudioRecord");
            readMethod = audioRecordClass.getMethod("read", byte[].class, int.class, int.class);
        } catch (Throwable throwable) {
            lastError = throwable;
            RecordableMod.LOGGER.warn("Android microphone capture failed to prepare read loop", throwable);
            return;
        }

        int frameSize = channels * (bitsPerSample / 8);
        int readBufferSize = Math.max(minBufferSize, 4096);
        readBufferSize = Math.max(readBufferSize, frameSize * 256);
        byte[] readBuffer = new byte[readBufferSize];

        try (FileOutputStream fileOutputStream = new FileOutputStream(outputFile.toFile());
             BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream, 64 * 1024)) {
            writeWavHeader(bufferedOutputStream, sampleRate, channels, bitsPerSample, 0);

            long totalDataBytes = 0L;
            while (running.get()) {
                if (audioRecord == null) {
                    break;
                }
                int bytesRead;
                try {
                    bytesRead = (Integer) readMethod.invoke(audioRecord, readBuffer, 0, readBuffer.length);
                } catch (Throwable throwable) {
                    lastError = throwable;
                    RecordableMod.LOGGER.warn("Android microphone capture read failed", throwable);
                    break;
                }

                if (bytesRead > 0) {
                    bufferedOutputStream.write(readBuffer, 0, bytesRead);
                    totalDataBytes += bytesRead;
                } else if (bytesRead == 0) {
                    Thread.sleep(10L);
                } else {
                    break;
                }
            }

            bufferedOutputStream.flush();
            updateWavHeader(outputFile, totalDataBytes, sampleRate, channels, bitsPerSample);
            RecordableMod.LOGGER.info("Android microphone capture finished: {} bytes written to {}", totalDataBytes, outputFile);
        } catch (Throwable throwable) {
            lastError = throwable;
            RecordableMod.LOGGER.warn("Android microphone capture failed during write loop", throwable);
        }
    }

    private static void writeWavHeader(java.io.OutputStream out, int sampleRate, int channels, int bitsPerSample, long dataSize) throws IOException {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        long chunkSize = 36 + dataSize;

        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, (int) chunkSize);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));

        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, 16);
        writeLittleEndianShort(out, (short) 1);
        writeLittleEndianShort(out, (short) channels);
        writeLittleEndianInt(out, sampleRate);
        writeLittleEndianInt(out, byteRate);
        writeLittleEndianShort(out, (short) blockAlign);
        writeLittleEndianShort(out, (short) bitsPerSample);

        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, (int) dataSize);
    }

    private static void updateWavHeader(Path outputFile, long dataSize, int sampleRate, int channels, int bitsPerSample) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.seek(4);
            writeIntLE(raf, (int) (36 + dataSize));
            raf.seek(40);
            writeIntLE(raf, (int) dataSize);
        }
    }

    private static void writeLittleEndianInt(java.io.OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(java.io.OutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeIntLE(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }
}
