package dev.recordable.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin that conditionally loads version-specific mixins.
 *
 * <p>This enables a single JAR to support Minecraft 1.20.x through 1.21.11 (the
 * final 1.x release) by selecting the correct mixin classes based on the
 * detected runtime version.</p>
 *
 * <p><b>IMPORTANT:</b> This plugin runs during Mixin preparation, before game
 * classes are loaded. It must <em>never</em> reference Minecraft classes or use
 * reflection to load them (e.g. {@code Class.forName("net.minecraft.SharedConstants")}).
 * Doing so causes {@code MixinTargetAlreadyLoadedException} for mods like
 * Lithium that mixin into transitively loaded classes (NbtCompound, etc.).</p>
 *
 * <p>Version detection uses only the Fabric Loader metadata API, which reads
 * the version from {@code fabric.mod.json} / launcher metadata without loading
 * any game classes.</p>
 *
 * <p><b>Note:</b> Minecraft 26.x (year-based versioning, starting March 2026)
 * requires Java 25, Mojang official mappings, and Fabric Loom 1.15+. It is
 * fundamentally incompatible with this build and would need a separate branch.</p>
 *
 * <h3>Version-specific mixin strategy:</h3>
 * <ul>
 *   <li><b>GameRendererMixin</b> (1.21+) / <b>GameRendererMixin_1_20</b> (1.20.x):
 *       Different render() signatures (RenderTickCounter vs float tickDelta)</li>
 *   <li><b>AdvancementToastMixin</b> (1.21+) / <b>AdvancementToastMixin_1_20</b> (1.20.x):
 *       AdvancementEntry vs Advancement class</li>
 *   <li><b>GuiRenderStateMixin</b> (1.21.6+ only):
 *       Class doesn't exist in 1.20.x or early 1.21.x</li>
 *   <li><b>SoundEngineMixin</b>: Same signature across all versions</li>
 *   <li><b>TitleScreenMixin</b>: Same API across all versions</li>
 * </ul>
 */
public class RecordableMixinPlugin implements IMixinConfigPlugin {

    private static Boolean is121Plus = null;
    private static Boolean is1216Plus = null;

    @Override
    public void onLoad(String mixinPackage) {
        // Detect version using Fabric Loader metadata - NO game class loading!
        try {
            Version mcVersion = FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .orElseThrow(() -> new RuntimeException("minecraft mod container not found"))
                    .getMetadata()
                    .getVersion();

            is121Plus = mcVersion.compareTo(parseVersion("1.21")) >= 0;
            is1216Plus = mcVersion.compareTo(parseVersion("1.21.6")) >= 0;
        } catch (Throwable t) {
            // If version detection fails, assume we're on the compile target (1.21.11)
            is121Plus = true;
            is1216Plus = true;
        }
    }

    /**
     * Parses a version string using Fabric's version parser.
     * This avoids any game class loading - purely metadata-based.
     */
    private static Version parseVersion(String versionStr) {
        try {
            return Version.parse(versionStr);
        } catch (VersionParsingException e) {
            throw new RuntimeException("Failed to parse version: " + versionStr, e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Extract simple class name from fully qualified mixin name
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);

        return switch (simpleName) {
            // 1.21.0 - 1.21.5: immediate GUI pipeline, capture after InGameHud.render
            case "GameRendererMixin" -> is121Plus && !is1216Plus;
            // 1.21.6+: deferred GUI pipeline, capture after GuiRenderer.render(GpuBufferSlice)
            case "GameRendererMixin_1216" -> is1216Plus;
            // 1.20.x version of GameRenderer mixin (float tickDelta signature)
            case "GameRendererMixin_1_20" -> !is121Plus;

            // 1.21+ version of AdvancementToast mixin (AdvancementEntry)
            case "AdvancementToastMixin" -> is121Plus;
            // 1.20.x version of AdvancementToast mixin (Advancement)
            case "AdvancementToastMixin_1_20" -> !is121Plus;

            // GuiRenderState only exists in 1.21.6+
            case "GuiRenderStateMixin" -> is1216Plus;

            // These work across all supported versions (stable signatures)
            case "ProjectileEntityMixin", "SoundEngineMixin", "TitleScreenMixin" -> true;

            // Default: allow any unrecognized mixins
            default -> true;
        };
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
