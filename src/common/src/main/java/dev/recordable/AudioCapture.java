package dev.recordable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio device detection and probing for FFmpeg-based audio capture.
 *
 * <p>This class detects available audio capture devices on the current platform
 * and provides the appropriate FFmpeg input arguments. It supports Windows
 * (DirectShow), Linux (PulseAudio), and macOS (AVFoundation).</p>
 */
public final class AudioCapture {

    /** Result of audio device probing. */
    public record AudioDeviceStatus(
            boolean available,
            String deviceName,
            String platform,
            String message,
            List<String> ffmpegArgs
    ) {
        public static AudioDeviceStatus unavailable(String platform, String message) {
            return new AudioDeviceStatus(false, "", platform, message, Collections.emptyList());
        }

        public static AudioDeviceStatus found(String deviceName, String platform, String message, List<String> ffmpegArgs) {
            return new AudioDeviceStatus(true, deviceName, platform, message, ffmpegArgs);
        }

        public String displayText() {
            if (available) {
                return "Audio: " + deviceName + " (" + platform + ")";
            }
            return "Audio: Not available - " + message;
        }
    }

    private static volatile AudioDeviceStatus cachedStatus;
    private static volatile CacheKey cachedKey;
    private static volatile long cachedStatusAtMs;
    private static final long CACHE_TTL_MS = 10_000L;

    private static final Pattern DSHOW_AUDIO_DEVICE = Pattern.compile("\"([^\"]+)\"\\s+\\(audio\\)");
    private static final Pattern AVFOUNDATION_DEVICE_NAME = Pattern.compile("\\[(\\d+)]\\s+(.+)$");

    private AudioCapture() {
    }

    /**
     * Detects available audio capture devices using Java's built-in sound API.
     * This works independently of FFmpeg and can be used as a fallback.
     *
     * @return list of available audio device names that support capture
     */
    public static List<String> detectJavaSoundDevices() {
        return JavaAudioCapture.getAvailableDeviceNames();
    }

    /**
     * Checks if any loopback audio device is available via Java sound API.
     * Loopback devices (Stereo Mix, PulseAudio monitors, etc.) capture system audio.
     *
     * @return true if a loopback device is available
     */
    public static boolean isJavaLoopbackAvailable() {
        return JavaAudioCapture.isLoopbackAvailable();
    }

    /**
     * Checks if any audio capture device is available via Java sound API.
     *
     * @return true if at least one capture device exists
     */
    public static boolean isJavaCaptureAvailable() {
        return JavaAudioCapture.isAnyCaptureDeviceAvailable();
    }

    /**
     * Returns a status description for Java audio capture availability,
     * suitable for display in the settings screen.
     *
     * @return human-readable status string
     */
    public static String getJavaAudioStatus() {
        List<JavaAudioCapture.AudioDeviceInfo> devices = JavaAudioCapture.detectAudioDevices();
        if (devices.isEmpty()) {
            return "No audio capture devices detected";
        }

        long loopbackCount = devices.stream()
                .filter(device -> device != null && device.isLoopback())
                .count();
        if (loopbackCount > 0) {
            String bestDevice = devices.stream()
                    .filter(device -> device != null && device.isLoopback())
                    .findFirst()
                    .map(device -> device.name())
                    .orElse("Unknown");
            return "Loopback: " + bestDevice + " (" + loopbackCount + " device" + (loopbackCount > 1 ? "s" : "") + ")";
        }

        return devices.size() + " capture device" + (devices.size() > 1 ? "s" : "") + " (no loopback)";
    }

    /**
     * Detects the best available audio capture device for the current platform.
     * Results are cached for {@value CACHE_TTL_MS}ms.
     *
     * @param ffmpegExecutable the FFmpeg executable path (from FfmpegStatus)
     * @param configuredDevice user-configured device name, or "auto" for auto-detect
     * @return status with device info and FFmpeg args
     */
    public static AudioDeviceStatus detectAudioDevice(String ffmpegExecutable, String configuredDevice) {
        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        String configured = configuredDevice == null ? "" : configuredDevice.trim();
        if (isAutoDevicePreference(configured)) {
            configured = "auto";
        }
        CacheKey requestedKey = new CacheKey(executable, configured, getPlatform());

        long now = System.currentTimeMillis();
        AudioDeviceStatus cached = cachedStatus;
        CacheKey key = cachedKey;
        if (cached != null && key != null && requestedKey.equals(key) && now - cachedStatusAtMs < CACHE_TTL_MS) {
            return cached;
        }

        AudioDeviceStatus status = probeAudioDevice(executable, configured);
        cachedStatus = status;
        cachedKey = requestedKey;
        cachedStatusAtMs = now;
        return status;
    }

    /** Clears the cached status so the next call to detectAudioDevice re-probes. */
    public static void clearCache() {
        cachedStatus = null;
        cachedKey = null;
        cachedStatusAtMs = 0L;
    }

    /**
     * Returns the OS category: "windows", "linux", "macos", "android", or "unknown".
     * Delegates to {@link PlatformUtils} for consistent platform detection.
     */
    public static String getPlatform() {
        return PlatformUtils.getPlatformId();
    }

    /**
     * Runs a short FFmpeg probe to verify that the selected audio device can be opened.
     * This does not guarantee non-silent input, but catches common binding failures.
     */
    public static boolean testAudioDevice(String ffmpegExecutable, AudioDeviceStatus status) {
        if (status == null || !status.available()) {
            return false;
        }

        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        List<String> args = status.ffmpegArgs();
        if (args == null || args.isEmpty()) {
            args = switch (status.platform() == null ? "" : status.platform().toLowerCase(Locale.ROOT)) {
                case "windows" -> buildWindowsInputArgs(status.deviceName());
                case "linux" -> List.of("-f", "pulse", "-i", status.deviceName());
                case "macos" -> List.of("-f", "avfoundation", "-i", ":" + status.deviceName());
                default -> Collections.emptyList();
            };
        }
        if (args.isEmpty()) {
            return false;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-nostdin");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.addAll(args);
        cmd.add("-t");
        cmd.add("1");
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");

        ProcessResult result = runCommand(6, cmd.toArray(String[]::new));
        if (!result.success()) {
            logProcessOutput("Audio device probe", result);
        }
        return result.success();
    }

    /**
     * Lists available microphone (audio input) device names for the current platform.
     * Used to populate the microphone selection control in the settings screen.
     *
     * @param ffmpegExecutable the FFmpeg executable path
     * @return list of device names ("auto" is always usable but not included here)
     */
    public static List<String> listMicrophoneDevices(String ffmpegExecutable) {
        if (PlatformUtils.isAndroid()) {
            return List.of("Android Microphone");
        }

        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        try {
            return switch (getPlatform()) {
                // On Windows, exclude system-audio loopback sources (e.g. "Stereo Mix") so the
                // selection list only contains real microphone/input devices.
                case "windows" -> listDShowAudioDevices(executable).stream()
                        .filter(d -> !isLoopbackDeviceName(d))
                        .collect(java.util.stream.Collectors.toList());
                case "macos" -> listAVFoundationAudioDevices(executable);
                case "linux" -> listPulseInputSources();
                default -> Collections.emptyList();
            };
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Failed to list microphone devices", t);
            return Collections.emptyList();
        }
    }

    /**
     * Resolves the FFmpeg input arguments needed to capture the microphone.
     *
     * <p>This is a SECOND audio input, captured in parallel with the primary game audio.
     * Unlike system/loopback capture, this targets a real input (capture) device such as
     * a headset mic. When {@code configuredDevice} is "auto"/blank, the platform default
     * input is used.</p>
     *
     * @param ffmpegExecutable the FFmpeg executable path
     * @param configuredDevice user-configured microphone device name, or "auto"
     * @return status with mic device info and FFmpeg input args, or unavailable
     */
    public static AudioDeviceStatus detectMicrophoneDevice(String ffmpegExecutable, String configuredDevice) {
        if (PlatformUtils.isAndroid()) {
            return AudioDeviceStatus.found(
                    "Android Microphone",
                    "android",
                    "Android microphone input via AudioRecord",
                    Collections.emptyList()
            );
        }

        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        boolean isAuto = isAutoDevicePreference(configuredDevice);
        String explicit = isAuto ? null : configuredDevice.trim();
        String platform = getPlatform();

        return switch (platform) {
            case "windows" -> probeWindowsMicrophone(executable, explicit);
            case "linux" -> probeLinuxMicrophone(explicit);
            case "macos" -> probeMacOSMicrophone(executable, explicit);
            default -> AudioDeviceStatus.unavailable(platform, "Microphone capture not supported on this platform.");
        };
    }

    /** Result of a short microphone test capture. */
    public record MicTestResult(
            boolean success,
            String deviceName,
            double meanDb,
            double maxDb,
            String message
    ) {
        /** True when the test captured audible signal (not silence / not a failure). */
        public boolean hasSignal() {
            return success && maxDb > -55.0;
        }
    }

    /**
     * Records a short sample from the configured microphone (using the exact same DirectShow/
     * PulseAudio/AVFoundation path used during recording) and measures its level. This lets the
     * user verify the mic actually delivers sound before recording, and distinguishes a working
     * mic from one blocked/muted by the OS (which captures only silence).
     *
     * <p>Blocking: runs ffmpeg for {@code seconds} plus a short measurement pass. Call off the
     * render thread.</p>
     *
     * @param ffmpegExecutable ffmpeg path/command
     * @param configuredDevice the configured mic device ("auto" or an explicit name)
     * @param seconds           capture duration (clamped 1..5)
     * @return a {@link MicTestResult} describing the outcome
     */
    public static MicTestResult testMicrophoneLevel(String ffmpegExecutable, String configuredDevice, int seconds) {
        String executable = ffmpegExecutable == null || ffmpegExecutable.isBlank() ? "ffmpeg" : ffmpegExecutable.trim();
        int dur = Math.max(1, Math.min(5, seconds));
        AudioDeviceStatus mic = detectMicrophoneDevice(executable, configuredDevice);
        if (mic == null || !mic.available()) {
            return new MicTestResult(false, configuredDevice == null ? "auto" : configuredDevice, Double.NaN, Double.NaN,
                    mic == null ? "No microphone detected." : mic.message());
        }

        Path tmp = null;
        try {
            tmp = Files.createTempFile("recordable-mictest-", ".wav");
            List<String> cmd = new ArrayList<>();
            cmd.add(executable);
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-y");
            cmd.add("-thread_queue_size");
            cmd.add("1024");
            cmd.addAll(mic.ffmpegArgs());
            cmd.add("-t");
            cmd.add(Integer.toString(dur));
            cmd.add("-ac");
            cmd.add("1");
            cmd.add("-ar");
            cmd.add("48000");
            cmd.add(tmp.toAbsolutePath().toString());

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder errBuf = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) {
                    if (errBuf.length() < 2000) {
                        errBuf.append(ln).append('\n');
                    }
                }
            }
            if (!p.waitFor(dur + 8L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new MicTestResult(false, mic.deviceName(), Double.NaN, Double.NaN,
                        "Mic test timed out opening the device.");
            }
            if (!Files.exists(tmp) || Files.size(tmp) <= 44L) {
                return new MicTestResult(false, mic.deviceName(), Double.NaN, Double.NaN,
                        "Mic device did not open: " + errBuf.toString().trim());
            }

            double[] levels = measureWavLevels(executable, tmp);
            double meanDb = levels[0];
            double maxDb = levels[1];
            if (Double.isNaN(meanDb)) {
                return new MicTestResult(true, mic.deviceName(), Double.NaN, Double.NaN,
                        "Captured " + dur + "s but level could not be measured.");
            }
            String msg;
            if (maxDb <= -55.0) {
                msg = "Only SILENCE captured (peak " + fmtDb(maxDb) + "). Enable mic access in Windows "
                        + "Privacy settings and unmute/raise the level (and check Razer Synapse).";
                return new MicTestResult(true, mic.deviceName(), meanDb, maxDb, msg);
            }
            msg = "Mic OK - peak " + fmtDb(maxDb) + ", avg " + fmtDb(meanDb) + ".";
            return new MicTestResult(true, mic.deviceName(), meanDb, maxDb, msg);
        } catch (Exception e) {
            return new MicTestResult(false, mic.deviceName(), Double.NaN, Double.NaN,
                    "Mic test failed: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String fmtDb(double db) {
        if (Double.isNaN(db) || Double.isInfinite(db)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f dB", db);
    }

    /** Returns {mean_volume, max_volume} in dB from ffmpeg volumedetect, or {NaN, NaN}. */
    private static double[] measureWavLevels(String ffmpegExecutable, Path wav) {
        double mean = Double.NaN;
        double max = Double.NaN;
        try {
            List<String> cmd = List.of(
                    ffmpegExecutable, "-nostdin", "-i", wav.toAbsolutePath().toString(),
                    "-af", "volumedetect", "-vn", "-f", "null", "-"
            );
            Process probe = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) {
                    if (ln.contains("mean_volume")) {
                        mean = parseDbAfter(ln, "mean_volume:");
                    } else if (ln.contains("max_volume")) {
                        max = parseDbAfter(ln, "max_volume:");
                    }
                }
            }
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        return new double[]{mean, max};
    }

    private static double parseDbAfter(String line, String key) {
        try {
            String[] parts = line.split(Pattern.quote(key));
            if (parts.length > 1) {
                return Double.parseDouble(parts[1].trim().split("\\s+")[0]);
            }
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }

    private static AudioDeviceStatus probeWindowsMicrophone(String ffmpegExecutable, String explicitDevice) {
        String device = explicitDevice;
        if (device == null) {
            List<String> devices = listDShowAudioDevices(ffmpegExecutable);
            // Auto: prefer a real input device (microphone/headset), NOT a loopback/Stereo Mix
            // source. The loopback device is already used to capture game audio, so selecting it
            // here would either fail to open twice or simply duplicate the game audio.
            List<String> realInputs = new ArrayList<>();
            for (String d : devices) {
                if (!isLoopbackDeviceName(d)) {
                    realInputs.add(d);
                }
            }
            device = pickPreferredDevice(realInputs, new String[]{
                    "microphone", "mic", "headset", "input", "line in", "webcam"
            });
            if (device == null && !realInputs.isEmpty()) {
                // No keyword match, but there is at least one non-loopback input device.
                device = realInputs.get(0);
            }
            if (device == null) {
                // Only loopback-type devices exist (e.g. only "Stereo Mix" is enabled). There is
                // no real microphone to capture, so report unavailable rather than duplicating
                // the game audio.
                return AudioDeviceStatus.unavailable("windows",
                        "No microphone (DirectShow input) device detected. "
                                + "Only loopback devices were found. Enable/plug in a microphone, "
                                + "or pick a device explicitly in settings.");
            }
        }
        if (device == null || device.isBlank()) {
            return AudioDeviceStatus.unavailable("windows", "No microphone (DirectShow input) device detected.");
        }
        List<String> args = buildWindowsInputArgs(device);
        return AudioDeviceStatus.found(device, "windows", "Microphone: " + device, args);
    }

    /**
     * Heuristic: returns true when the given DirectShow device name looks like a system-audio
     * loopback / monitor source rather than a real microphone input. These are used for capturing
     * <em>game</em> audio (OBS-style) and must never be auto-selected as the microphone.
     */
    private static boolean isLoopbackDeviceName(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String lower = deviceName.toLowerCase(Locale.ROOT);
        return lower.contains("stereo mix")
                || lower.contains("stereomix")
                || lower.contains("what u hear")
                || lower.contains("what you hear")
                || lower.contains("wave out mix")
                || lower.contains("wave out")
                || lower.contains("loopback")
                || lower.contains("monitor of")
                || lower.contains("rec. playback");
    }

    private static AudioDeviceStatus probeLinuxMicrophone(String explicitDevice) {
        if (explicitDevice != null) {
            return AudioDeviceStatus.found(explicitDevice, "linux", "Microphone: " + explicitDevice,
                    List.of("-f", "pulse", "-i", explicitDevice));
        }
        // Auto: the PulseAudio "default" source is the user's selected input (microphone).
        String source = findPulseInputSource();
        String target = source != null ? source : "default";
        return AudioDeviceStatus.found(target, "linux", "Microphone: " + target,
                List.of("-f", "pulse", "-i", target));
    }

    private static AudioDeviceStatus probeMacOSMicrophone(String ffmpegExecutable, String explicitDevice) {
        if (explicitDevice != null) {
            String target = explicitDevice;
            if (!explicitDevice.matches("\\d+")) {
                List<String> devices = listAVFoundationAudioDevices(ffmpegExecutable);
                int idx = devices.indexOf(explicitDevice);
                if (idx >= 0) {
                    target = Integer.toString(idx);
                }
            }
            return AudioDeviceStatus.found(explicitDevice, "macos", "Microphone: " + explicitDevice,
                    List.of("-f", "avfoundation", "-i", ":" + target));
        }
        // Auto: AVFoundation audio index 0 is the default input device.
        return AudioDeviceStatus.found("Default Input", "macos", "Microphone: default input",
                List.of("-f", "avfoundation", "-i", ":0"));
    }

    /** Lists non-monitor PulseAudio sources (i.e. real capture/input devices like microphones). */
    private static List<String> listPulseInputSources() {
        ProcessResult result = runCommand(3, "pactl", "list", "short", "sources");
        if (!result.success() && !result.hasAnyOutput()) {
            return Collections.emptyList();
        }
        List<String> inputs = new ArrayList<>();
        for (String line : mergeOutputLines(result)) {
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String sourceName = parts[1].trim();
            if (!sourceName.endsWith(".monitor") && !inputs.contains(sourceName)) {
                inputs.add(sourceName);
            }
        }
        return inputs;
    }

    /** Finds the best PulseAudio input (microphone) source, skipping monitor sources. */
    private static String findPulseInputSource() {
        List<String> inputs = listPulseInputSources();
        if (inputs.isEmpty()) {
            return null;
        }
        for (String input : inputs) {
            String lower = input.toLowerCase(Locale.ROOT);
            if (lower.contains("input") || lower.contains("mic")) {
                return input;
            }
        }
        return inputs.get(0);
    }

    private static AudioDeviceStatus probeAudioDevice(String ffmpegExecutable, String configuredDevice) {
        String platform = getPlatform();
        boolean isAuto = isAutoDevicePreference(configuredDevice);

        return switch (platform) {
            case "linux" -> probeLinuxAudio(isAuto ? null : configuredDevice);
            case "windows" -> probeWindowsAudio(ffmpegExecutable, isAuto ? null : configuredDevice);
            case "macos" -> probeMacOSAudio(ffmpegExecutable, isAuto ? null : configuredDevice);
            case "android" -> probeAndroidAudio();
            default -> AudioDeviceStatus.unavailable(platform, "Unsupported operating system for audio capture.");
        };
    }

    private static AudioDeviceStatus probeAndroidAudio() {
        try {
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            if (loopback.isActive()) {
                return AudioDeviceStatus.found(
                        "OpenAL Loopback",
                        "android",
                        "Game audio captured directly via OpenAL loopback (48kHz Stereo).",
                        Collections.emptyList()
                );
            }
        } catch (Throwable ignored) {}

        try {
            if (OpenALLoopbackCapture.isLoopbackSupported()) {
                return AudioDeviceStatus.found(
                        "OpenAL Loopback (pending)",
                        "android",
                        "OpenAL loopback supported. Audio will activate when recording starts.",
                        Collections.emptyList()
                );
            }
        } catch (Throwable ignored) {}

        return AudioDeviceStatus.found(
                "OpenAL Capture",
                "android",
                "Audio captured via OpenAL capture device.",
                Collections.emptyList()
        );
    }

    private static AudioDeviceStatus probeLinuxAudio(String explicitDevice) {
        if (explicitDevice != null) {
            List<String> args = List.of("-f", "pulse", "-i", explicitDevice);
            return AudioDeviceStatus.found(explicitDevice, "linux",
                    "Using configured PulseAudio source: " + explicitDevice, args);
        }

        String monitorSource = findPulseMonitorSource();
        if (monitorSource != null) {
            List<String> args = List.of("-f", "pulse", "-i", monitorSource);
            return AudioDeviceStatus.found(monitorSource, "linux",
                    "PulseAudio monitor source detected.", args);
        }

        List<String> args = List.of("-f", "pulse", "-i", "default");
        return AudioDeviceStatus.found("default", "linux",
                "Using default PulseAudio source (monitor source not found).", args);
    }

    /**
     * Uses {@code pactl list short sources} to find a monitor source.
     */
    private static String findPulseMonitorSource() {
        ProcessResult result = runCommand(3, "pactl", "list", "short", "sources");
        if (!result.success() && !result.hasAnyOutput()) {
            return null;
        }

        List<String> monitors = new ArrayList<>();
        for (String line : mergeOutputLines(result)) {
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String sourceName = parts[1].trim();
            if (sourceName.endsWith(".monitor")) {
                monitors.add(sourceName);
            }
        }

        if (monitors.isEmpty()) {
            return null;
        }
        for (String monitor : monitors) {
            if (monitor.contains("analog")) {
                return monitor;
            }
        }
        return monitors.get(0);
    }

    private static AudioDeviceStatus probeWindowsAudio(String ffmpegExecutable, String explicitDevice) {
        if (explicitDevice != null) {
            String trimmedDevice = explicitDevice.trim();
            if (trimmedDevice.isEmpty()) {
                return AudioDeviceStatus.unavailable("windows", "Configured audio device name is empty.");
            }

            String dshowDevice = resolveDshowCaptureTarget(ffmpegExecutable, trimmedDevice);
            List<String> args = buildWindowsInputArgs(dshowDevice);
            return AudioDeviceStatus.found(dshowDevice, "windows",
                    "Using configured DirectShow device: " + dshowDevice, args);
        }

        String dshowDevice = detectWindowsAudioDirectShow(ffmpegExecutable);
        if (dshowDevice != null) {
            String captureTarget = resolveDshowCaptureTarget(ffmpegExecutable, dshowDevice);
            List<String> args = buildWindowsInputArgs(captureTarget);
            return AudioDeviceStatus.found(captureTarget, "windows",
                    "DirectShow audio device detected (Stereo Mix/loopback input).", args);
        }

        return AudioDeviceStatus.unavailable("windows",
                "Stereo Mix was not detected. Recording will continue in video-only mode. " +
                        "Enable Stereo Mix in Windows Sound Settings > Recording > Show Disabled Devices, then retry.");
    }

    private static String detectWindowsAudioDirectShow(String ffmpegExecutable) {
        RecordableMod.LOGGER.info("Scanning Windows audio devices via DirectShow...");
        List<String> dshowDevices = listDShowAudioDevices(ffmpegExecutable);
        RecordableMod.LOGGER.info("DirectShow audio devices found: {}", dshowDevices);
        if (dshowDevices.isEmpty()) {
            return null;
        }

        String best = pickPreferredDevice(dshowDevices, new String[]{
                "stereo mix", "what u hear", "cable output", "voicemeeter", "vb-audio", "loopback",
                "speakers", "headphones", "headset", "realtek"
        });
        if (best != null) {
            return best;
        }
        return dshowDevices.get(0);
    }

    private static List<String> buildWindowsInputArgs(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return Collections.emptyList();
        }

        String trimmed = deviceName.trim();

        // -use_wallclock_as_timestamps 1 makes FFmpeg stamp each incoming dshow packet with the
        // host clock at arrival time instead of trusting the device's own DTS. Buggy DirectShow
        // drivers (e.g. Razer Kraken V4 X) emit backward-jumping/non-monotonic DTS that otherwise
        // get clamped and slowly accumulate A/V drift over a long recording; wall-clock stamping
        // gives a strictly-monotonic source timeline. It pairs with the capture-stage
        // aresample=async filter (in FFmpegEncoder) which absorbs the residual jitter into a
        // continuous, sample-accurate WAV.
        return List.of(
                "-rtbufsize", "200M",
                "-f", "dshow",
                "-audio_buffer_size", "30",
                "-use_wallclock_as_timestamps", "1",
                "-i", "audio=" + trimmed
        );
    }

    private static String resolveDshowCaptureTarget(String ffmpegExecutable, String preferredDevice) {
        if (preferredDevice == null || preferredDevice.isBlank()) {
            return preferredDevice;
        }

        String trimmed = preferredDevice.trim();
        if (trimmed.startsWith("@device_")) {
            return trimmed;
        }

        RecordableMod.LOGGER.info("Using DirectShow device name as-is: {}", trimmed);
        return trimmed;
    }

    private static String pickPreferredDevice(List<String> devices, String[] priorityTokens) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }

        for (String token : priorityTokens) {
            String loweredToken = token.toLowerCase(Locale.ROOT);
            for (String device : devices) {
                if (device != null && device.toLowerCase(Locale.ROOT).contains(loweredToken)) {
                    return device;
                }
            }
        }

        return null;
    }

    private static boolean isAutoDevicePreference(String configuredDevice) {
        if (configuredDevice == null) {
            return true;
        }
        String normalized = configuredDevice.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "auto".equals(normalized) || "openal".equals(normalized);
    }

    /**
     * Runs {@code ffmpeg -list_devices true -f dshow -i dummy} to list DirectShow audio devices.
     * Parses device names from FFmpeg stderr output.
     *
     * <p>FFmpeg outputs device listings in this format:</p>
     * <pre>
     * [dshow @ 0x...] "Device Name" (video)
     * [dshow @ 0x...]   Alternative name "@device_..."
     * [dshow @ 0x...] "Device Name" (audio)
     * [dshow @ 0x...]   Alternative name "@device_..."
     * </pre>
     *
     * <p>Or in newer FFmpeg versions:</p>
     * <pre>
     * [in#0 @ 0x...] "Device Name" (audio)
     * [in#0 @ 0x...]   Alternative name "@device_cm_..."
     * </pre>
     *
     * <p>We match lines containing {@code "NAME" (audio)} using the
     * {@link #DSHOW_AUDIO_DEVICE} pattern, which is reliable across FFmpeg versions.</p>
     */
    private static List<String> listDShowAudioDevices(String ffmpegExecutable) {
        ProcessResult result = runCommand(8,
                ffmpegExecutable,
                "-list_devices", "true",
                "-f", "dshow",
                "-i", "dummy");

        logProcessOutput("DirectShow device detection", result);

        List<String> outputLines = mergeOutputLines(result);
        if (outputLines.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> devices = new ArrayList<>();

        for (String line : outputLines) {
            if (line == null) {
                continue;
            }

            String trimmed = line.trim();

            if (trimmed.toLowerCase(Locale.ROOT).contains("alternative name")) {
                continue;
            }

            Matcher matcher = DSHOW_AUDIO_DEVICE.matcher(trimmed);
            while (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (!candidate.isEmpty() && !devices.contains(candidate)) {
                    devices.add(candidate);
                    RecordableMod.LOGGER.info("Found DirectShow audio device: {}", candidate);
                }
            }
        }

        return devices;
    }

    private static AudioDeviceStatus probeMacOSAudio(String ffmpegExecutable, String explicitDevice) {
        if (explicitDevice != null) {
            List<String> args;
            if (explicitDevice.matches("\\d+")) {
                args = List.of("-f", "avfoundation", "-i", ":" + explicitDevice);
            } else {
                args = List.of("-f", "avfoundation", "-i", ":" + explicitDevice);
            }
            return AudioDeviceStatus.found(explicitDevice, "macos",
                    "Using configured AVFoundation device: " + explicitDevice, args);
        }

        List<String> avDevices = listAVFoundationAudioDevices(ffmpegExecutable);

        String[] preferredDevices = {"BlackHole", "Soundflower", "Loopback", "Multi-Output"};
        for (int i = 0; i < avDevices.size(); i++) {
            String device = avDevices.get(i);
            for (String preferred : preferredDevices) {
                if (device.toLowerCase(Locale.ROOT).contains(preferred.toLowerCase(Locale.ROOT))) {
                    List<String> args = List.of("-f", "avfoundation", "-i", ":" + i);
                    return AudioDeviceStatus.found(device, "macos",
                            "Virtual audio device detected for system audio capture.", args);
                }
            }
        }

        if (!avDevices.isEmpty()) {
            List<String> args = List.of("-f", "avfoundation", "-i", ":0");
            return AudioDeviceStatus.found(avDevices.get(0), "macos",
                    "Using default audio input. Install BlackHole for system audio capture.",
                    args);
        }

        return AudioDeviceStatus.unavailable("macos",
                "No AVFoundation audio devices detected. Install BlackHole or Soundflower for system audio capture.");
    }

    private static List<String> listAVFoundationAudioDevices(String ffmpegExecutable) {
        ProcessResult result = runCommand(5,
                ffmpegExecutable,
                "-f", "avfoundation",
                "-list_devices", "true",
                "-i", "");
        if (!result.success() && !result.hasAnyOutput()) {
            return Collections.emptyList();
        }

        List<String> devices = new ArrayList<>();
        boolean inAudioSection = false;
        for (String line : mergeOutputLines(result)) {
            String trimmed = line.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("avfoundation audio devices")) {
                inAudioSection = true;
                continue;
            }
            if (lower.contains("avfoundation video devices")) {
                inAudioSection = false;
                continue;
            }
            if (!inAudioSection) {
                continue;
            }

            Matcher matcher = AVFOUNDATION_DEVICE_NAME.matcher(trimmed);
            if (matcher.find()) {
                String name = matcher.group(2).trim();
                if (!name.isEmpty()) {
                    devices.add(name);
                }
            }
        }

        return devices;
    }

    private static ProcessResult runCommand(int timeoutSeconds, String... command) {
        List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            Thread stdoutThread = new Thread(() -> readProcessStream(process.getInputStream(), stdoutLines),
                    "Record-able cmd stdout");
            Thread stderrThread = new Thread(() -> readProcessStream(process.getErrorStream(), stderrLines),
                    "Record-able cmd stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            boolean exited = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }

            joinThreadQuietly(stdoutThread, 500L);
            joinThreadQuietly(stderrThread, 500L);

            int exitCode = exited ? process.exitValue() : -1;
            boolean success = exited && exitCode == 0;
            String error = success ? "" : (exited ? "Exit code " + exitCode : "Timed out");

            return new ProcessResult(
                    success,
                    List.copyOf(stdoutLines),
                    List.copyOf(stderrLines),
                    exitCode,
                    error
            );
        } catch (Exception exception) {
            RecordableMod.LOGGER.debug("Failed to execute command: {}", String.join(" ", command), exception);
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return new ProcessResult(false, Collections.emptyList(), Collections.emptyList(), -1, message);
        }
    }

    private static void readProcessStream(java.io.InputStream stream, List<String> sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.add(line);
            }
        } catch (Exception exception) {
            RecordableMod.LOGGER.debug("Failed to read process stream", exception);
        }
    }

    private static void joinThreadQuietly(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> mergeOutputLines(ProcessResult result) {
        if (result == null) {
            return Collections.emptyList();
        }
        List<String> merged = new ArrayList<>(result.stderrLines());
        if (merged.isEmpty()) {
            merged.addAll(result.stdoutLines());
        } else if (!result.stdoutLines().isEmpty()) {
            merged.addAll(result.stdoutLines());
        }
        return merged;
    }

    private static void logProcessOutput(String label, ProcessResult result) {
        if (result == null) {
            return;
        }

        String stdoutContent = result.stdoutLines().isEmpty()
                ? "<empty>"
                : String.join(System.lineSeparator(), result.stdoutLines());
        String stderrContent = result.stderrLines().isEmpty()
                ? "<empty>"
                : String.join(System.lineSeparator(), result.stderrLines());

        RecordableMod.LOGGER.info("{} exitCode={} success={} error='{}'", label, result.exitCode(), result.success(), result.error());
        RecordableMod.LOGGER.info("{} STDOUT:{}{}", label, System.lineSeparator(), stdoutContent);
        RecordableMod.LOGGER.info("{} STDERR:{}{}", label, System.lineSeparator(), stderrContent);
    }

    private record ProcessResult(boolean success, List<String> stdoutLines, List<String> stderrLines, int exitCode,
                                 String error) {
        private boolean hasAnyOutput() {
            return !stdoutLines.isEmpty() || !stderrLines.isEmpty();
        }
    }

    private record CacheKey(String ffmpegExecutable, String configuredDevice, String platform) {
        private CacheKey {
            ffmpegExecutable = ffmpegExecutable == null ? "" : ffmpegExecutable;
            configuredDevice = configuredDevice == null ? "" : configuredDevice;
            platform = platform == null ? "unknown" : platform;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CacheKey other)) {
                return false;
            }
            return Objects.equals(ffmpegExecutable, other.ffmpegExecutable)
                    && Objects.equals(configuredDevice, other.configuredDevice)
                    && Objects.equals(platform, other.platform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ffmpegExecutable, configuredDevice, platform);
        }
    }
}
