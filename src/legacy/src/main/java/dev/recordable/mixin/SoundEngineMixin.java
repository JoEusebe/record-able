package dev.recordable.mixin;

import dev.recordable.OpenALLoopbackCapture;
import dev.recordable.RecordableMod;
import dev.recordable.ReplayCompatBridge;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * Replaces Minecraft's normal OpenAL audio device with a loopback device
 * so that all game audio can be captured directly at full volume.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Redirect {@code openDeviceOrFallback} to return a loopback device</li>
 *   <li>Redirect {@code alcCreateContext} to inject loopback format attributes</li>
 *   <li>After init(), start the render thread</li>
 * </ol>
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Unique
    private static boolean recordable$usingLoopback = false;

    /**
     * Redirects the device opening to use a loopback device instead of a real one.
     */
    @Redirect(
            method = "init",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sound/SoundEngine;openDeviceOrFallback(Ljava/lang/String;)J")
    )
    private static long recordable$redirectOpenDevice(String deviceSpecifier) {
        recordable$usingLoopback = false;

        // Compatibility bridge: when a replay mod is present, yield the OpenAL
        // loopback device to it instead of competing for ownership.
        if (ReplayCompatBridge.shouldYieldAudioDevice()) {
            RecordableMod.LOGGER.info("[Recordable] Yielding OpenAL loopback to {} (compatibility bridge); using normal audio device.",
                    ReplayCompatBridge.getPresentReplayModName());
            return recordable$openNormalDevice(deviceSpecifier);
        }

        try {
            if (!OpenALLoopbackCapture.isLoopbackSupported()) {
                RecordableMod.LOGGER.info("ALC_SOFT_loopback not supported, using normal audio");
                return recordable$openNormalDevice(deviceSpecifier);
            }

            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            long device = loopback.openLoopbackDevice();
            if (device == 0L) {
                RecordableMod.LOGGER.warn("Loopback device failed, using normal audio");
                return recordable$openNormalDevice(deviceSpecifier);
            }

            recordable$usingLoopback = true;
            RecordableMod.LOGGER.info("SoundEngine using loopback device: {}", device);
            return device;
        } catch (Throwable t) {
            RecordableMod.LOGGER.error("Loopback setup error, using normal audio", t);
            return recordable$openNormalDevice(deviceSpecifier);
        }
    }

    /**
     * Redirects context creation to use loopback attributes when a loopback device is active.
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

    /**
     * Diagnostic: confirms the mixin is applied and the init redirects are about
     * to run. If this line never appears in the log, the mixin did not apply to
     * this Minecraft version and game-audio loopback cannot work.
     */
    @Inject(method = "init", at = @At("HEAD"))
    private void recordable$beforeInit(String deviceSpecifier, boolean enableHrtf, CallbackInfo ci) {
        RecordableMod.LOGGER.info("[Recordable] SoundEngineMixin active - attempting OpenAL loopback for game-audio capture");
    }

    /**
     * After init() completes, start the render thread.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void recordable$afterInit(String deviceSpecifier, boolean enableHrtf, CallbackInfo ci) {
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
                RecordableMod.LOGGER.warn("[Recordable] Game-audio loopback is NOT active after SoundEngine init. "
                        + "Recordings will fall back to system audio capture, which is often silent or only background noise. "
                        + "Most common causes: OpenAL loopback is unavailable on this runtime, or another recording mod "
                        + "(Flashback, ReplayMod, etc.) redirected OpenAL first. "
                        + "Try disabling other recording mods and keep only Record-able for game audio.");
            }
        }
    }

    /**
     * Before close(), shut down loopback capture.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void recordable$beforeClose(CallbackInfo ci) {
        if (recordable$usingLoopback) {
            try {
                OpenALLoopbackCapture.getInstance().shutdown();
            } catch (Throwable ignored) {}
            recordable$usingLoopback = false;
        }
    }

    /**
     * Opens a normal (non-loopback) audio device, replicating SoundEngine.openDeviceOrFallback logic.
     */
    @Unique
    private static long recordable$openNormalDevice(String deviceSpecifier) {
        long device = 0L;
        if (deviceSpecifier != null) {
            device = ALC10.alcOpenDevice(deviceSpecifier);
        }
        if (device == 0L) {
            String available = SoundEngine.findAvailableDeviceSpecifier();
            if (available != null) {
                device = ALC10.alcOpenDevice(available);
            }
        }
        if (device == 0L) {
            device = ALC10.alcOpenDevice((CharSequence) null);
        }
        if (device == 0L) {
            throw new IllegalStateException("Failed to open OpenAL device");
        }
        return device;
    }
}
