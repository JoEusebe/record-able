package de.maxhenkel.voicechat.api;

import de.maxhenkel.voicechat.api.events.EventRegistration;

public interface VoicechatPlugin {
    String getPluginId();

    default void initialize(VoicechatApi api) {
    }

    default void registerEvents(EventRegistration registration) {
    }
}
