package dev.recordable;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;
import de.maxhenkel.voicechat.api.events.VoicechatDisableEvent;

/**
 * Optional Simple Voice Chat integration for Record-able.
 *
 * <p>This class is only ever loaded when the Simple Voice Chat mod (by Max Henkel) is
 * installed: it is declared as a {@code voicechat} entrypoint in each variant's
 * {@code fabric.mod.json}, and Fabric only instantiates that entrypoint when Simple
 * Voice Chat requests it. The Simple Voice Chat API is a {@code compileOnly}
 * dependency, so Record-able builds and runs perfectly fine without the mod present.</p>
 *
 * <p>It listens for the decoded audio of other players ({@link ClientReceiveSoundEvent})
 * and the local microphone ({@link ClientSoundEvent}) and forwards the raw PCM to
 * {@link VoiceChatIntegration}, which mixes and (when appropriate) overlays it into the
 * recording. The audio is never modified or cancelled, so voice chat behaves exactly as
 * usual for the player.</p>
 */
public class RecordableVoicechatPlugin implements VoicechatPlugin {

    // Last-seen Simple Voice Chat client API, captured from initialize and from
    // every client event. Used by the on-demand state refresher so Record-able can
    // pull the live mute / disable state when a recording starts (not just when a
    // mute / disable event happens to fire).
    private static volatile VoicechatClientApi clientApi;

    @Override
    public String getPluginId() {
        return "record-able";
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceChatIntegration.markAvailable();
        if (api instanceof VoicechatClientApi) {
            clientApi = (VoicechatClientApi) api;
        }
        // Let VoiceChatIntegration pull the freshest mute / disable state on demand.
        VoiceChatIntegration.setStateRefresher(RecordableVoicechatPlugin::refreshFromClientApi);
    }

    /** Refreshes the cached mute / disable state from the last-seen client API. */
    private static void refreshFromClientApi() {
        refreshMicState(clientApi);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // Decoded audio of every other player the client can hear.
        //
        // Simple Voice Chat dispatches its events by exact class (an internal
        // Map<Class, listeners> keyed on the concrete event class), so a listener
        // registered on the base ClientReceiveSoundEvent interface never fires:
        // the mod only ever dispatches the three concrete subtypes below. We must
        // register each subtype explicitly. Because dispatch is exact-class, every
        // received frame is delivered to exactly one of these handlers, so there is
        // no risk of double-counting the audio.
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event -> {
            try {
                VoiceChatIntegration.onReceiveAudio(event.getId(), event.getRawAudio());
            } catch (Throwable ignored) {
            }
        });
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, event -> {
            try {
                VoiceChatIntegration.onReceiveAudio(event.getId(), event.getRawAudio());
            } catch (Throwable ignored) {
            }
        });
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, event -> {
            try {
                VoiceChatIntegration.onReceiveAudio(event.getId(), event.getRawAudio());
            } catch (Throwable ignored) {
            }
        });
        // The local player's own microphone audio (status/diagnostics only). While
        // these frames flow the mic is definitely being listened to, so refresh the
        // live mute / disable state from the client API on every frame.
        registration.registerEvent(ClientSoundEvent.class, event -> {
            try {
                VoiceChatIntegration.onMicAudio(event.getRawAudio());
                refreshMicState(event.getVoicechat());
            } catch (Throwable ignored) {
            }
        });
        // Microphone mute toggled: update the listening state immediately so
        // Record-able stops recording the mic while Simple Voice Chat is muted.
        registration.registerEvent(MicrophoneMuteEvent.class, event -> {
            try {
                VoiceChatIntegration.updateMicrophoneState(event.isDisabled(),
                        isVoicechatDisabled(event.getVoicechat()));
            } catch (Throwable ignored) {
            }
        });
        // Voice chat enabled / disabled entirely: mirror the state so the mic gate
        // closes when the whole feature is disabled.
        registration.registerEvent(VoicechatDisableEvent.class, event -> {
            try {
                VoiceChatIntegration.updateMicrophoneState(
                        isMicrophoneMuted(event.getVoicechat()), event.isDisabled());
            } catch (Throwable ignored) {
            }
        });
    }

    /** Refreshes the cached mic mute / disable state from the client API. */
    private static void refreshMicState(VoicechatClientApi api) {
        if (api == null) return;
        clientApi = api;
        try {
            VoiceChatIntegration.updateMicrophoneState(api.isMuted(), api.isDisabled());
        } catch (Throwable ignored) {
        }
    }

    private static boolean isMicrophoneMuted(VoicechatClientApi api) {
        if (api != null) clientApi = api;
        try {
            return api != null && api.isMuted();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVoicechatDisabled(VoicechatClientApi api) {
        if (api != null) clientApi = api;
        try {
            return api != null && api.isDisabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
