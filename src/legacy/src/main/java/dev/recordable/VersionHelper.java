package dev.recordable;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;

/**
 * Version-safe utility methods that bridge API differences between
 * Minecraft 1.20.x and 1.21.x.
 *
 * <p><b>IMPORTANT - no reflection for Identifier.of().</b>
 * Previously this class used {@code Identifier.class.getMethod("of", ...)}
 * to probe for the 1.21+ static factory. This broke at runtime because
 * Fabric's intermediary mappings rename {@code of} → {@code method_XXXXX},
 * so the reflection lookup always failed with {@code NoSuchMethodException},
 * then the constructor fallback also failed.</p>
 *
 * <p>The fix: call {@code Identifier.of()} directly - Fabric Loom remaps
 * it during compilation. On 1.20.x where this method doesn't exist, catch
 * {@code NoSuchMethodError} and fall back to the constructor via
 * reflection (since the constructor is private at compile time against
 * 1.21.11 but was public in 1.20.x at runtime).</p>
 *
 * <p>Version detection uses Fabric Loader's metadata API (no game class
 * loading) instead of the MultiVersion V.java framework.</p>
 */
public final class VersionHelper {
    private static volatile Boolean cachedIs121Plus = null;
    /** Cached 1.20.x Identifier constructor - only resolved once on first use. */
    private static volatile Constructor<Identifier> identifierCtor = null;

    private VersionHelper() {
    }

    /**
     * Creates an {@link Identifier} in a version-safe way.
     *
     * <ul>
     *   <li>1.21+: Uses {@code Identifier.of(namespace, path)} - Loom remaps this</li>
     *   <li>1.20.x: Uses {@code new Identifier(namespace, path)} via reflection
     *       (constructor is private at compile time but public at 1.20.x runtime)</li>
     * </ul>
     *
     * @param namespace the namespace (e.g., "recordable")
     * @param path      the path (e.g., "main")
     * @return the created Identifier
     */
    public static Identifier id(String namespace, String path) {
        try {
            // 1.21+: static factory method - Fabric Loom remaps this call automatically
            return Identifier.of(namespace, path);
        } catch (NoSuchMethodError e) {
            // 1.20.x: constructor was public at runtime (private in 1.21.11 compile target)
            return createIdentifier120(namespace, path);
        }
    }

    /**
     * Fallback for 1.20.x: use reflection to access the Identifier(String, String)
     * constructor which was public in 1.20.x but is private in the 1.21.11 compile target.
     */
    private static Identifier createIdentifier120(String namespace, String path) {
        try {
            if (identifierCtor == null) {
                Constructor<Identifier> ctor = Identifier.class.getDeclaredConstructor(String.class, String.class);
                ctor.setAccessible(true);
                identifierCtor = ctor;
            }
            return identifierCtor.newInstance(namespace, path);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to create Identifier for " + namespace + ":" + path, ex);
        }
    }

    /**
     * Shorthand for creating a Record-able mod identifier.
     *
     * @param path the path (e.g., "main")
     * @return Identifier with namespace "recordable"
     */
    public static Identifier modId(String path) {
        return id(RecordableMod.MOD_ID, path);
    }

    /**
     * Returns true if running on Minecraft 1.21 or higher.
     * Uses Fabric Loader metadata - no game class loading.
     */
    public static boolean is121Plus() {
        if (cachedIs121Plus != null) {
            return cachedIs121Plus;
        }
        try {
            Version mcVersion = FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .orElseThrow(() -> new RuntimeException("minecraft mod container not found"))
                    .getMetadata()
                    .getVersion();
            cachedIs121Plus = mcVersion.compareTo(parseVersion("1.21")) >= 0;
        } catch (Throwable t) {
            cachedIs121Plus = true; // assume compile target (1.21.11)
        }
        return cachedIs121Plus;
    }

    /**
     * Returns true if running on Minecraft 1.20.x (any 1.20 sub-version).
     */
    public static boolean is120x() {
        return !is121Plus();
    }

    /**
     * Returns a human-readable version string for logging.
     * Uses Fabric Loader metadata - no game class loading.
     */
    public static String getVersionInfo() {
        try {
            String version = FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .orElseThrow()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
            return "MC " + version + " (Fabric Loader detected)";
        } catch (Throwable t) {
            return "MC version unknown (Fabric Loader query failed)";
        }
    }

    private static Version parseVersion(String versionStr) {
        try {
            return Version.parse(versionStr);
        } catch (VersionParsingException e) {
            throw new RuntimeException("Failed to parse version: " + versionStr, e);
        }
    }
}
