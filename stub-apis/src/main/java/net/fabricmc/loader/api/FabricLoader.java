package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.Optional;

public interface FabricLoader {
    static FabricLoader getInstance() {
        throw new UnsupportedOperationException("stub");
    }

    Path getConfigDir();

    Path getGameDir();

    boolean isModLoaded(String id);

    Optional<ModContainer> getModContainer(String id);
}
