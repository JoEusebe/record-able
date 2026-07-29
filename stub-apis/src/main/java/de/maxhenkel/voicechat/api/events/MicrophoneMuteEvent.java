package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatClientApi;

public interface MicrophoneMuteEvent extends Event {
    boolean isDisabled();

    VoicechatClientApi getVoicechat();
}
