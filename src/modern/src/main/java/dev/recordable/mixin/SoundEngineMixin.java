package dev.recordable.mixin;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import dev.recordable.OpenALLoopbackCapture;
import dev.recordable.RecordableMod;
import dev.recordable.ReplayCompatBridge;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * OpenAL loopback audio capture - <b>Modern (26.x+)</b>.
 *
 * <p><b>IMPORTANT (root-cause fix):</b> In Minecraft 1.21.x the OpenAL device/context
 * was opened by {@code net.minecraft.client.sound.SoundEngine} (Yarn name). In the
 * official Mojang names shipped with 26.x that exact class is
 * {@code com.mojang.blaze3d.audio.Library} - <b>not</b>
 * {@code net.minecraft.client.sounds.SoundEngine} (which is the higher-level sound
 * manager and no longer touches the device/context at all).</p>
 *
 * <p>The previous Modern mixin targeted {@code SoundEngine.init} and redirected
 * {@code SoundEngine.openDeviceOrFallback(String)J} + {@code alcCreateContext}. Those
 * injection points <b>do not exist</b> on 26.x, so with {@code defaultRequire = 0} the
 * mixin silently applied nothing. Loopback never engaged, audio fell back to system/OS
 * capture, and recordings contained only the OS loopback noise floor (flat broadband
 * hiss) instead of game audio.</p>
 *
 * <p>This version targets {@link Library#init(String, DeviceList, boolean)} and redirects
 * the real call sites:</p>
 * <ul>
 *   <li>{@code Library.openDeviceOrFallback(String, String)J} - open a loopback device.</li>
 *   <li>{@code ALC10.alcCreateContext(J, IntBuffer)J} - supply the loopback format.</li>
 * </ul>
 */
@Mixin(Library.class)
public abstract class SoundEngineMixin {

    @Unique private boolean recordable$usingLoopback = false;

    /**
     * Redirects the private {@code Library.openDeviceOrFallback(String, String)} call
     * inside {@link Library#init}. Opens a loopback device when supported; otherwise
     * opens a normal device exactly like vanilla would.
     */
    @Redirect(
            method = "init",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback(Ljava/lang/String;Ljava/lang/String;)J")
    )
    private long recordable$redirectOpenDevice(String deviceSpecifier, String defaultDevice) {
        recordable$usingLoopback = false;

        // Compatibility bridge: when a replay mod is present, yield the OpenAL
        // loopback device to it instead of competing for ownership.
        if (ReplayCompatBridge.shouldYieldAudioDevice()) {
            RecordableMod.LOGGER.info("[Recordable] Yielding OpenAL loopback to {} (compatibility bridge); using normal audio device.",
                    ReplayCompatBridge.getPresentReplayModName());
            return recordable$openNormalDevice(deviceSpecifier, defaultDevice);
        }

        try {
            if (!OpenALLoopbackCapture.isLoopbackSupported()) {
                RecordableMod.LOGGER.info("ALC_SOFT_loopback not supported, using normal audio");
                return recordable$openNormalDevice(deviceSpecifier, defaultDevice);
            }
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            long device = loopback.openLoopbackDevice();
            if (device == 0L) {
                RecordableMod.LOGGER.warn("Loopback device failed, using normal audio");
                return recordable$openNormalDevice(deviceSpecifier, defaultDevice);
            }
            recordable$usingLoopback = true;
            RecordableMod.LOGGER.info("blaze3d Library using loopback device: {}", device);
            return device;
        } catch (Throwable t) {
            RecordableMod.LOGGER.error("Loopback setup error, using normal audio", t);
            return recordable$openNormalDevice(deviceSpecifier, defaultDevice);
        }
    }

    /**
     * Redirects the {@code alcCreateContext} call inside {@link Library#init} so that the
     * loopback device is created with the PCM render format Record-able expects
     * (48 kHz, stereo, 16-bit). When loopback is not active, the vanilla attributes are used.
     */
    @Redirect(
            method = "init",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J")
    )
    private long recordable$redirectCreateContext(long device, IntBuffer originalAttrs) {
        if (!recordable$usingLoopback) {
            return ALC10.alcCreateContext(device, originalAttrs);
        }
        OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
        int[] loopbackAttrs = loopback.getContextAttributes();
        RecordableMod.LOGGER.info("Creating context with loopback format: {}Hz stereo 16-bit",
                OpenALLoopbackCapture.SAMPLE_RATE);
        return ALC10.alcCreateContext(device, loopbackAttrs);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void recordable$beforeInit(String deviceSpecifier, DeviceList deviceList, boolean enableHrtf, CallbackInfo ci) {
        RecordableMod.LOGGER.info("[Recordable] Library(SoundEngine) mixin active - attempting OpenAL loopback for game-audio capture");
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void recordable$afterInit(String deviceSpecifier, DeviceList deviceList, boolean enableHrtf, CallbackInfo ci) {
        if (recordable$usingLoopback) {
            OpenALLoopbackCapture.getInstance().startRenderThread();
            RecordableMod.LOGGER.info("[Recordable] Loopback render thread started - game audio captured directly from OpenAL");
        } else {
            boolean flashbackPresent = ReplayCompatBridge.isReplayModPresent() && 
                    "flashback".equalsIgnoreCase(ReplayCompatBridge.getPresentReplayModId());
            
            if (flashbackPresent && ReplayCompatBridge.shouldYieldAudioDevice()) {
                RecordableMod.LOGGER.info("[Recordable] Game-audio loopback YIELDED to Flashback (compatibility mode). "
                        + "This recording will use system audio instead. To restore game-audio capture, "
                        + "disable Flashback or change the 'Replay Mod Compatibility' setting in Record-able options.");
            } else if (flashbackPresent) {
                RecordableMod.LOGGER.warn("[Recordable] Flashback conflict detected but compatibility bridge is disabled. "
                        + "The loopback device may be owned by Flashback, causing Record-able to capture NO game audio. "
                        + "Enable 'Replay Mod Compatibility' in Record-able settings to yield audio priority to Flashback.");
            } else {
                RecordableMod.LOGGER.warn("[Recordable] Game-audio loopback is NOT active after audio init. "
                        + "Recordings will fall back to system audio capture, which is often silent or only background noise. "
                        + "Most common causes: OpenAL loopback is unavailable on this runtime, or another recording mod "
                        + "(Flashback, ReplayMod, etc.) redirected OpenAL first. "
                        + "Try disabling other recording mods and keep only Record-able for game audio.");
            }
        }
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void recordable$beforeCleanup(CallbackInfo ci) {
        if (recordable$usingLoopback) {
            try { OpenALLoopbackCapture.getInstance().shutdown(); } catch (Throwable ignored) {}
            recordable$usingLoopback = false;
        }
    }

    @Unique
    private long recordable$openNormalDevice(String deviceSpecifier, String defaultDevice) {
        long device = 0L;
        if (deviceSpecifier != null) device = ALC10.alcOpenDevice(deviceSpecifier);
        if (device == 0L && defaultDevice != null) device = ALC10.alcOpenDevice(defaultDevice);
        if (device == 0L) device = ALC10.alcOpenDevice((CharSequence) null);
        if (device == 0L) throw new IllegalStateException("Failed to open OpenAL device");
        return device;
    }
}
