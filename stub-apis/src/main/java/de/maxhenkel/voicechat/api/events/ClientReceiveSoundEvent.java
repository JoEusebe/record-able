package de.maxhenkel.voicechat.api.events;

import java.util.UUID;

public interface ClientReceiveSoundEvent extends Event {
    UUID getId();

    short[] getRawAudio();

    interface EntitySound extends ClientReceiveSoundEvent {
    }

    interface LocationalSound extends ClientReceiveSoundEvent {
    }

    interface StaticSound extends ClientReceiveSoundEvent {
    }
}
