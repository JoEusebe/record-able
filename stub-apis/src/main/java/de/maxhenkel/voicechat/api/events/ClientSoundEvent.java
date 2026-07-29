package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatClientApi;

public interface ClientSoundEvent extends Event {
    short[] getRawAudio();

    VoicechatClientApi getVoicechat();
}
