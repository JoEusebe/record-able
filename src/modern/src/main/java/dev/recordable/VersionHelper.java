package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Version-safe utility methods for the <b>modern (26.x+)</b> build.
 *
 * <p>In Minecraft 26.x the codebase is unobfuscated and uses Mojang official
 * names. {@code Identifier.of()} → {@code Identifier.fromNamespaceAndPath()}.</p>
 *
 * <p>Version detection uses Fabric Loader metadata API - no V.java reflection.</p>
 */
public final class VersionHelper {
    private VersionHelper() {}

    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier modId(String path) {
        return id(RecordableMod.MOD_ID, path);
    }

    public static boolean is121Plus() {
        return true; // Always true on 26.x
    }

    public static boolean is120x() {
        return false; // Never on 26.x
    }

    // Cached reflection handles for resolving the current screen across 26.x.
    // 26.1.2 and earlier: the screen lives in a public field on Minecraft (Minecraft.screen).
    // 26.2 and later: the screen moved to a getter on the Gui (Minecraft.gui.screen()).
    private static volatile boolean screenAccessProbed = false;
    private static Method guiScreenMethod;   // Gui.screen() on 26.2+
    private static Field minecraftScreenField; // Minecraft.screen on 26.1.2-

    /**
     * Returns the currently open screen in a way that is safe across the whole 26.x range.
     *
     * <p>Avoids referencing {@code Gui.screen()} directly (which does not exist on 26.1.2)
     * and {@code Minecraft.screen} directly (which does not exist on 26.2). Both paths are
     * resolved reflectively and cached on first use.</p>
     *
     * @param client the Minecraft client instance (may be null)
     * @return the active screen, or {@code null} if none is open or it cannot be resolved
     */
    public static Screen currentScreen(Minecraft client) {
        if (client == null) return null;

        if (!screenAccessProbed) {
            synchronized (VersionHelper.class) {
                if (!screenAccessProbed) {
                    // Prefer the 26.2+ Gui.screen() getter.
                    try {
                        Method m = client.gui.getClass().getMethod("screen");
                        m.setAccessible(true);
                        guiScreenMethod = m;
                    } catch (Throwable ignored) {
                        // Fall back to the 26.1.2- Minecraft.screen field.
                        try {
                            Field f = Minecraft.class.getDeclaredField("screen");
                            f.setAccessible(true);
                            minecraftScreenField = f;
                        } catch (Throwable ignored2) {
                            // Neither path is available; currentScreen() will return null.
                        }
                    }
                    screenAccessProbed = true;
                }
            }
        }

        try {
            if (guiScreenMethod != null) {
                return (Screen) guiScreenMethod.invoke(client.gui);
            }
            if (minecraftScreenField != null) {
                return (Screen) minecraftScreenField.get(client);
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("VersionHelper.currentScreen() reflection failed", t);
        }
        return null;
    }

    public static String getVersionInfo() {
        try {
            String version = FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .orElseThrow()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
            return "MC " + version + " (Modern build, unobfuscated)";
        } catch (Throwable t) {
            return "MC version unknown (Modern build)";
        }
    }
}
