package dev.recordable.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.recordable.screen.RecordableSettingsScreen;

/** Soft Mod Menu bridge. Loaded only when Mod Menu requests the optional entrypoint. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RecordableSettingsScreen::new;
    }
}
