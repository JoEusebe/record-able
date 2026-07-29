package dev.gxlg.multiversion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime Minecraft version detection and comparison utility.
 * Supports Minecraft 1.20.x through 1.21.11 (the final 1.x release).
 *
 * <p>As of March 2026, Minecraft switched to year-based versioning (26.x).
 * The numeric parser handles 26.x format correctly (major.minor.patch), but
 * the mod's dependency range excludes those versions since they require a
 * completely different toolchain (Java 25, Mojang mappings, Loom 1.15).</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   if (V.higher("1.20")) { ... }   // > 1.20
 *   if (V.atLeast("1.21")) { ... }  // >= 1.21
 *   if (V.lower("1.21")) { ... }    // < 1.21
 *   if (V.equal("1.20.4")) { ... }  // == 1.20.4
 *   if (V.between("1.20", "1.21.1")) { ... }  // >= 1.20 && <= 1.21.1
 * </pre>
 */
@SuppressWarnings("unused")
public class V {
    private static volatile MinecraftVersion version = null;

    public static MinecraftVersion getVersion() {
        MinecraftVersion cached = version;
        if (cached != null) {
            return cached;
        }
        // Double-checked locking: this is called from multiple threads (render,
        // network, capture, etc.), so the lazy detection below must not run
        // concurrently and its result must be published safely.
        synchronized (V.class) {
            if (version != null) {
                return version;
            }
            // Try multiple approaches to detect the Minecraft version at runtime.
            // This handles both intermediary and named mappings across 1.20-1.21.x.
            try {
                // Approach 1: SharedConstants → GameVersion → getName() (1.20-1.21.x)
                R.RClass constants = R.clz("net.minecraft.class_155/net.minecraft.SharedConstants");
                try {
                    // 1.20.x-1.21.x: GameVersion interface with getName()
                    R.RClass gameVersionClz = R.clz(
                            "net.minecraft.class_6489/com.mojang.bridge.game.GameVersion"
                    );
                    Object gameVersion = constants.mthd(
                            "method_16673/getCurrentVersion", gameVersionClz.self()
                    ).invk();
                    // Try getName() first (1.20.x-1.21.1)
                    try {
                        version = new MinecraftVersion((String) gameVersionClz.inst(gameVersion)
                                .mthd("method_48019/getName", String.class).invk());
                    } catch (Exception ignored) {
                        // Fallback: try name() component accessor (records in newer versions)
                        version = new MinecraftVersion((String) gameVersionClz.inst(gameVersion)
                                .mthd("comp_4025/name", String.class).invk());
                    }
                } catch (Exception ignored) {
                    // Approach 2: WorldVersion interface (1.21.2+)
                    R.RClass gameVersionClz = R.clz(
                            "net.minecraft.class_6489/net.minecraft.WorldVersion"
                    );
                    Object gameVersion = constants.mthd(
                            "method_16673/getCurrentVersion", gameVersionClz.self()
                    ).invk();
                    version = new MinecraftVersion((String) gameVersionClz.inst(gameVersion)
                            .mthd("comp_4025/name", String.class).invk());
                }
            } catch (Exception e) {
                // Last resort: try system property or default
                String sysProp = System.getProperty("minecraft.version");
                if (sysProp != null && !sysProp.isEmpty()) {
                    version = new MinecraftVersion(sysProp);
                } else {
                    throw new RuntimeException("Failed to detect Minecraft version", e);
                }
            }
            return version;
        }
    }

    /** Returns true if the running version is strictly higher than {@code other}. */
    public static boolean higher(String other) {
        return getVersion().higher(other);
    }

    /** Returns true if the running version is equal to {@code other}. */
    public static boolean equal(String other) {
        return getVersion().equal(other);
    }

    /** Returns true if the running version is strictly lower than {@code other}. */
    public static boolean lower(String other) {
        return getVersion().lower(other);
    }

    /** Returns true if the running version is >= {@code other}. */
    public static boolean atLeast(String other) {
        return !lower(other);
    }

    /** Returns true if the running version is <= {@code other}. */
    public static boolean atMost(String other) {
        return !higher(other);
    }

    /** Returns true if the running version is >= {@code low} and <= {@code high}. */
    public static boolean between(String low, String high) {
        return atLeast(low) && atMost(high);
    }

    /** Returns the detected version as a string (e.g., "1.21.1"). */
    public static String getVersionString() {
        MinecraftVersion v = getVersion();
        if (v.patch == 0) return v.major + "." + v.minor;
        return v.major + "." + v.minor + "." + v.patch;
    }

    public static class MinecraftVersion {
        private final int major;
        private final int minor;
        private final int patch;

        private final Map<String, Integer> cache = new ConcurrentHashMap<>();

        public MinecraftVersion(String version) {
            String[] mainParts = version.split("[^0-9.]", 2);
            String[] nums = mainParts[0].split("\\.");
            this.major = nums.length > 0 ? Integer.parseInt(nums[0]) : 0;
            this.minor = nums.length > 1 ? Integer.parseInt(nums[1]) : 0;
            this.patch = nums.length > 2 ? Integer.parseInt(nums[2]) : 0;
        }

        public int compare(String other) {
            return cache.computeIfAbsent(
                other, i -> {
                    MinecraftVersion v = new MinecraftVersion(other);
                    if (this.major != v.major) {
                        return Integer.compare(this.major, v.major);
                    }
                    if (this.minor != v.minor) {
                        return Integer.compare(this.minor, v.minor);
                    }
                    return Integer.compare(this.patch, v.patch);
                }
            );
        }

        public boolean higher(String other) {
            return this.compare(other) > 0;
        }

        public boolean lower(String other) {
            return this.compare(other) < 0;
        }

        public boolean equal(String other) {
            return this.compare(other) == 0;
        }

        @Override
        public String toString() {
            if (patch == 0) return major + "." + minor;
            return major + "." + minor + "." + patch;
        }
    }
}
