package de.maxhenkel.voicechat.api.events;

import java.util.function.Consumer;

public interface EventRegistration {
    <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> listener);
}
