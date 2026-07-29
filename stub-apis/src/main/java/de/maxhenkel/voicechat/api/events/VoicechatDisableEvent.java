package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatClientApi;

public interface VoicechatDisableEvent extends Event {
    boolean isDisabled();

    VoicechatClientApi getVoicechat();
}
