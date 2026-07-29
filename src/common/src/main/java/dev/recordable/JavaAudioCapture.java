package dev.recordable;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform audio capture using Java's built-in {@code javax.sound.sampled} API.
 *
 * <p>Captures audio from a system loopback/monitor device and writes it to a temporary
 * WAV file. Supports platform-specific device detection:</p>
 * <ul>
 *   <li><b>Windows:</b> Stereo Mix, Wave Out Mix, What U Hear</li>
 *   <li><b>Linux:</b> PulseAudio monitor devices</li>
 *   <li><b>macOS:</b> Loopback devices (BlackHole, Soundflower)</li>
 * </ul>
 *
 * <p>If no suitable loopback device is found, the capture gracefully returns {@code false}
 * from {@link #start()}, allowing the caller to continue with video-only recording.</p>
 */
public final class JavaAudioCapture {

    /** Information about a detected audio device. */
    public record AudioDeviceInfo(
            String name,
            Mixer.Info mixerInfo,
            boolean isLoopback,
            String platform
    ) {
        public String displayName() {
            return name + (isLoopback ? " (Loopback)" : "");
        }
    }

    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_SAMPLE_SIZE_BITS = 16;
    private static final int DEFAULT_CHANNELS = 2;
    private static final int BUFFER_SIZE_FRAMES = 4096;

    private final int sampleRate;
    private final int sampleSizeBits;
    private final int channels;
    private final Path outputWavFile;
    private final String preferredDevice;

    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private volatile Thread captureThread;
    private volatile TargetDataLine dataLine;
    private volatile AudioDeviceInfo activeDevice;
    private volatile String lastError = "";
    private volatile long capturedBytes;

    /**
     * Creates a new audio capture instance.
     *
     * @param sampleRate      sample rate in Hz (e.g. 44100 or 48000)
     * @param sampleSizeBits  bits per sample (typically 16)
     * @param channels        number of channels (1=mono, 2=stereo)
     * @param outputWavFile   path to write the captured audio WAV file
     * @param preferredDevice preferred device name, or "auto" for auto-detection
     */
    public JavaAudioCapture(int sampleRate, int sampleSizeBits, int channels,
                            Path outputWavFile, String preferredDevice) {
        this.sampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
        this.sampleSizeBits = sampleSizeBits > 0 ? sampleSizeBits : DEFAULT_SAMPLE_SIZE_BITS;
        this.channels = channels > 0 ? channels : DEFAULT_CHANNELS;
        this.outputWavFile = outputWavFile;
        this.preferredDevice = preferredDevice == null || preferredDevice.isBlank() ? "auto" : preferredDevice.trim();
    }

    /**
     * Creates a new audio capture with default settings.
     */
    public JavaAudioCapture(Path outputWavFile) {
        this(DEFAULT_SAMPLE_RATE, DEFAULT_SAMPLE_SIZE_BITS, DEFAULT_CHANNELS, outputWavFile, "auto");
    }

    /**
     * Attempts to start audio capture, trying multiple audio formats in order of preference.
     * Falls back through: 48kHz stereo → 44.1kHz stereo → 48kHz mono → 44.1kHz mono → 22kHz mono.
     *
     * @return {@code true} if capture started successfully, {@code false} if no suitable device/format was found
     */
    public boolean start() {
        if (capturing.get()) {
            RecordableMod.LOGGER.warn("JavaAudioCapture: already capturing.");
            return true;
        }

        AudioFormat[] formatsToTry = buildFormatCandidates();

        RecordableMod.LOGGER.info("JavaAudioCapture: will try {} audio format candidates.", formatsToTry.length);

        TargetDataLine line = null;
        AudioFormat successFormat = null;

        for (AudioFormat format : formatsToTry) {
            RecordableMod.LOGGER.debug("JavaAudioCapture: trying format: {}", format);
            try {
                line = openAudioDevice(format);
                if (line != null) {
                    successFormat = format;
                    RecordableMod.LOGGER.info("JavaAudioCapture: successfully opened device with format: {}", format);
                    break;
                }
            } catch (Exception e) {
                RecordableMod.LOGGER.debug("JavaAudioCapture: format {} failed: {}", format, e.getMessage());
            }
        }

        if (line == null || successFormat == null) {
            lastError = "No suitable audio capture device found for any supported format.";
            RecordableMod.LOGGER.warn("JavaAudioCapture: {}. Tried {} formats. Falling back to video-only.",
                    lastError, formatsToTry.length);
            return false;
        }

        dataLine = line;
        capturing.set(true);
        capturedBytes = 0;

        final TargetDataLine captureLine = line;
        final AudioFormat captureFormat = successFormat;
        captureThread = new Thread(() -> captureLoop(captureLine, captureFormat), "Record-able Java Audio Capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.NORM_PRIORITY + 1);
        captureThread.start();

        RecordableMod.LOGGER.info("JavaAudioCapture started: device='{}' format={} output={}",
                activeDevice != null ? activeDevice.name() : "unknown", captureFormat, outputWavFile);
        return true;
    }

    /**
     * Builds an ordered list of audio format candidates to try.
     * Prioritizes the user-configured sample rate and channels, then falls back
     * through common formats that are widely supported.
     */
    private AudioFormat[] buildFormatCandidates() {
        int[][] candidates = {
                { sampleRate, channels },       // user-configured first
                { 48000, 2 },                   // 48kHz stereo
                { 44100, 2 },                   // 44.1kHz stereo (CD quality)
                { 48000, 1 },                   // 48kHz mono
                { 44100, 1 },                   // 44.1kHz mono
                { 22050, 1 },                   // 22kHz mono (low-quality fallback)
        };

        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.List<AudioFormat> formats = new java.util.ArrayList<>();
        for (int[] c : candidates) {
            String key = c[0] + "-" + c[1];
            if (seen.add(key)) {
                int bits = sampleSizeBits > 0 ? sampleSizeBits : 16;
                formats.add(new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        c[0],              // sample rate
                        bits,              // bits per sample
                        c[1],              // channels
                        (bits / 8) * c[1], // frame size
                        c[0],              // frame rate = sample rate for PCM
                        false              // little-endian
                ));
            }
        }

        return formats.toArray(new AudioFormat[0]);
    }

    /**
     * Stops audio capture and finalizes the WAV file.
     */
    public void stop() {
        capturing.set(false);

        TargetDataLine line = dataLine;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception e) {
                RecordableMod.LOGGER.debug("JavaAudioCapture: error stopping data line.", e);
            }
            try {
                line.close();
            } catch (Exception e) {
                RecordableMod.LOGGER.debug("JavaAudioCapture: error closing data line.", e);
            }
            dataLine = null;
        }

        Thread thread = captureThread;
        if (thread != null) {
            try {
                thread.join(8000); // Allow enough time for header update
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                RecordableMod.LOGGER.warn("JavaAudioCapture: capture thread still alive after join timeout.");
            }
            captureThread = null;
        }

        RecordableMod.LOGGER.info("JavaAudioCapture stopped. Captured {} bytes to {}",
                capturedBytes, outputWavFile);
    }

    /**
     * @return true if currently capturing audio
     */
    public boolean isCapturing() {
        return capturing.get();
    }

    /**
     * @return the active audio device info, or null if not capturing
     */
    public AudioDeviceInfo getActiveDevice() {
        return activeDevice;
    }

    /**
     * @return the last error message, or empty string if no error
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * @return the output WAV file path
     */
    public Path getOutputFile() {
        return outputWavFile;
    }

    /**
     * @return total bytes captured so far
     */
    public long getCapturedBytes() {
        return capturedBytes;
    }

    private void captureLoop(TargetDataLine line, AudioFormat format) {
        int bufferSize = BUFFER_SIZE_FRAMES * format.getFrameSize();
        byte[] buffer = new byte[bufferSize];

        try {
            Files.createDirectories(outputWavFile.getParent());
        } catch (Exception e) {
            lastError = "Could not create audio output directory: " + e.getMessage();
            RecordableMod.LOGGER.warn("JavaAudioCapture: {}", lastError, e);
            return;
        }

        try (RandomAccessFile raf = new RandomAccessFile(outputWavFile.toFile(), "rw")) {
            writeWavHeader(raf, format, 0);
            long dataStart = raf.getFilePointer();

            try {
                while (capturing.get() && line.isOpen()) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        raf.write(buffer, 0, bytesRead);
                        capturedBytes += bytesRead;
                    } else if (bytesRead == -1) {
                        break;
                    }
                }
            } catch (Exception readEx) {
                RecordableMod.LOGGER.debug("JavaAudioCapture: capture read ended (line closed): {}", readEx.getMessage());
            }

            long dataSize = raf.getFilePointer() - dataStart;
            updateWavHeader(raf, dataSize);
            RecordableMod.LOGGER.info("JavaAudioCapture: WAV header updated. dataSize={} bytes, capturedBytes={}",
                    dataSize, capturedBytes);
        } catch (Exception e) {
            lastError = "Audio capture error: " + e.getMessage();
            RecordableMod.LOGGER.warn("JavaAudioCapture: capture loop failed.", e);
        }
    }

    private void writeWavHeader(RandomAccessFile raf, AudioFormat format, long dataSize) throws IOException {
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        short blockAlign = (short) (channels * (bitsPerSample / 8));

        raf.writeBytes("RIFF");
        writeIntLE(raf, (int) (36 + dataSize)); // chunk size
        raf.writeBytes("WAVE");

        raf.writeBytes("fmt ");
        writeIntLE(raf, 16); // sub-chunk size (PCM)
        writeShortLE(raf, (short) 1); // audio format (PCM)
        writeShortLE(raf, (short) channels);
        writeIntLE(raf, sampleRate);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, blockAlign);
        writeShortLE(raf, (short) bitsPerSample);

        raf.writeBytes("data");
        writeIntLE(raf, (int) dataSize); // data size placeholder
    }

    private void updateWavHeader(RandomAccessFile raf, long dataSize) throws IOException {
        raf.seek(4);
        writeIntLE(raf, (int) (36 + dataSize));

        raf.seek(40);
        writeIntLE(raf, (int) dataSize);
    }

    private static void writeIntLE(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile raf, short value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
    }

    /**
     * Attempts to open a suitable audio device for capture.
     */
    private TargetDataLine openAudioDevice(AudioFormat format) {
        if (!"auto".equalsIgnoreCase(preferredDevice)) {
            TargetDataLine line = tryOpenDevice(preferredDevice, format);
            if (line != null) {
                return line;
            }
            RecordableMod.LOGGER.warn("JavaAudioCapture: preferred device '{}' not found or failed. Trying auto-detect.", preferredDevice);
        }

        List<AudioDeviceInfo> devices = detectAudioDevices();
        RecordableMod.LOGGER.info("JavaAudioCapture: detected {} audio capture devices.", devices.size());

        for (AudioDeviceInfo device : devices) {
            if (device.isLoopback()) {
                TargetDataLine line = tryOpenMixer(device, format);
                if (line != null) {
                    return line;
                }
            }
        }

        for (AudioDeviceInfo device : devices) {
            if (!device.isLoopback()) {
                TargetDataLine line = tryOpenMixer(device, format);
                if (line != null) {
                    return line;
                }
            }
        }

        return null;
    }

    private TargetDataLine tryOpenDevice(String deviceName, AudioFormat format) {
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixerInfos) {
            if (info.getName().toLowerCase(Locale.ROOT).contains(deviceName.toLowerCase(Locale.ROOT))) {
                try {
                    Mixer mixer = AudioSystem.getMixer(info);
                    Line.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
                    if (mixer.isLineSupported(lineInfo)) {
                        TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                        line.open(format, BUFFER_SIZE_FRAMES * format.getFrameSize());
                        line.start();
                        activeDevice = new AudioDeviceInfo(info.getName(), info, isLoopbackDevice(info), getPlatformId());
                        RecordableMod.LOGGER.info("JavaAudioCapture: opened device '{}' with format {}", info.getName(), format);
                        return line;
                    }
                } catch (LineUnavailableException e) {
                    RecordableMod.LOGGER.debug("JavaAudioCapture: format not supported on device '{}': {}", info.getName(), e.getMessage());
                } catch (Exception e) {
                    RecordableMod.LOGGER.debug("JavaAudioCapture: failed to open device '{}'", info.getName(), e);
                }
            }
        }
        return null;
    }

    private TargetDataLine tryOpenMixer(AudioDeviceInfo device, AudioFormat format) {
        try {
            Mixer mixer = AudioSystem.getMixer(device.mixerInfo());
            Line.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
            if (!mixer.isLineSupported(lineInfo)) {
                return null;
            }
            TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
            line.open(format, BUFFER_SIZE_FRAMES * format.getFrameSize());
            line.start();
            activeDevice = device;
            RecordableMod.LOGGER.info("JavaAudioCapture: opened device '{}' (loopback={}) with format {}",
                    device.name(), device.isLoopback(), format);
            return line;
        } catch (LineUnavailableException e) {
            RecordableMod.LOGGER.debug("JavaAudioCapture: format {} not supported on device '{}': {}",
                    format, device.name(), e.getMessage());
            return null;
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("JavaAudioCapture: failed to open device '{}'", device.name(), e);
            return null;
        }
    }

    /**
     * Detects available audio capture devices with platform-specific loopback identification.
     *
     * @return list of detected audio devices, sorted with loopback devices first
     */
    public static List<AudioDeviceInfo> detectAudioDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        String platform = getPlatformId();

        AudioFormat[] testFormats = {
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000, 16, 2, 4, 48000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000, 16, 1, 2, 48000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 1, 2, 44100, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 22050, 16, 1, 2, 22050, false),
        };

        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        java.util.Set<String> seenMixers = new java.util.HashSet<>();

        for (Mixer.Info info : mixerInfos) {
            if (seenMixers.contains(info.getName())) continue;
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                for (AudioFormat fmt : testFormats) {
                    DataLine.Info targetLineInfo = new DataLine.Info(TargetDataLine.class, fmt);
                    if (mixer.isLineSupported(targetLineInfo)) {
                        boolean loopback = isLoopbackDevice(info);
                        devices.add(new AudioDeviceInfo(info.getName(), info, loopback, platform));
                        seenMixers.add(info.getName());
                        break; // found at least one supported format, add the device
                    }
                }
            } catch (Exception e) {
            }
        }

        devices.sort((a, b) -> Boolean.compare(b.isLoopback(), a.isLoopback()));
        return devices;
    }

    /**
     * Returns the names of all available audio capture devices.
     */
    public static List<String> getAvailableDeviceNames() {
        List<String> names = new ArrayList<>();
        for (AudioDeviceInfo device : detectAudioDevices()) {
            names.add(device.displayName());
        }
        return names;
    }

    /**
     * Checks if any loopback audio device is available for capture.
     */
    public static boolean isLoopbackAvailable() {
        return detectAudioDevices().stream().anyMatch(device -> device != null && device.isLoopback());
    }

    /**
     * Checks if any audio capture device is available at all.
     */
    public static boolean isAnyCaptureDeviceAvailable() {
        return !detectAudioDevices().isEmpty();
    }

    /**
     * Determines if a mixer is likely a loopback device based on its name,
     * using platform-specific heuristics.
     */
    private static boolean isLoopbackDevice(Mixer.Info info) {
        if (info == null) return false;
        String name = info.getName().toLowerCase(Locale.ROOT);
        String desc = (info.getDescription() != null ? info.getDescription() : "").toLowerCase(Locale.ROOT);
        String platform = getPlatformId();

        return switch (platform) {
            case "windows" -> isWindowsLoopback(name, desc);
            case "linux" -> isLinuxLoopback(name, desc);
            case "macos" -> isMacLoopback(name, desc);
            default -> false;
        };
    }

    private static boolean isWindowsLoopback(String name, String desc) {
        return name.contains("stereo mix")
                || name.contains("wave out mix")
                || name.contains("what u hear")
                || name.contains("what you hear")
                || name.contains("loopback")
                || name.contains("cable output")
                || name.contains("voicemeeter")
                || name.contains("vb-audio")
                || desc.contains("stereo mix")
                || desc.contains("loopback");
    }

    private static boolean isLinuxLoopback(String name, String desc) {
        return name.contains("monitor")
                || name.contains("loopback")
                || desc.contains("monitor of");
    }

    private static boolean isMacLoopback(String name, String desc) {
        return name.contains("blackhole")
                || name.contains("soundflower")
                || name.contains("loopback")
                || name.contains("multi-output")
                || desc.contains("loopback");
    }

    private static String getPlatformId() {
        try {
            return PlatformUtils.getPlatformId();
        } catch (Exception e) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return "windows";
            if (os.contains("mac") || os.contains("darwin")) return "macos";
            if (os.contains("nux") || os.contains("nix")) return "linux";
            return "unknown";
        }
    }
}
