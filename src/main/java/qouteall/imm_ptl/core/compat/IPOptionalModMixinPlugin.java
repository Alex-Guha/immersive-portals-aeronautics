/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skips mixins whose target class belongs to an optional mod that is not installed. Without this, Mixin logs an
 * "@Mixin target ... was not found" warning at boot for each of them (about 20 without DH, Shield Generators, Physics
 * Mod and Iris). Applying is unchanged: such a mixin had nothing to apply to. Targets of other packages always apply.
 * <p>
 * Also skips every mixin of our Sable and Create compat packages when that mod is absent, whatever its target: many
 * of them target vanilla classes (or ours) and reference the mod's classes, which fails when the target loads (Mixin
 * resolves the mod's classes while attaching; the game does not start without Sable otherwise). None of them does
 * anything without its mod.
 */
public class IPOptionalModMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String, String> MOD_BY_PACKAGE = Map.ofEntries(
        Map.entry("com.seibel.distanthorizons.", "distanthorizons"),
        Map.entry("me.cortex.voxy.", "voxy"),
        Map.entry("net.diebuddies.", "physicsmod"),
        Map.entry("com.anton.shieldgenerators.", "shield_generators"),
        Map.entry("net.irisshaders.", "iris"),
        Map.entry("team.lodestar.lodestone.", "lodestone"),
        Map.entry("com.cmdpro.databank.", "databank"),
        Map.entry("dev.ryanhcode.sable.", "sable"),
        Map.entry("dev.simulated_team.simulated.", "simulated"),
        Map.entry("dev.eriksonn.aeronautics.", "aeronautics"),
        Map.entry("toni.sodiumdynamiclights.", "sodiumdynamiclights"),
        Map.entry("net.createteleporters.", "createteleporters"),
        Map.entry("com.playsi.aero_cam_sync.", "aero_cam_sync"),
        Map.entry("com.euphoriapatches.euphoria_patcher.", "euphoria_patcher"),
        Map.entry("net.karto.mc2.mc2_interactivefoliage.", "mc2_interactivefoliage"),
        Map.entry("com.github.razorplay01.sway.", "sway")
    );

    private static final Map<String, String> MOD_BY_MIXIN_PACKAGE = Map.of(
        "qouteall.imm_ptl.core.compat.mixin_sable.", "sable",
        "qouteall.imm_ptl.core.compat.mixin_create.", "create",
        "qouteall.imm_ptl.core.compat.mixin_voxy.", "voxy"
    );

    @Override
    public void onLoad(String mixinPackage) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    // mixins of mixin_voxy that reference Iris's classes while targeting Voxy's (or vanilla) classes
    private static final Set<String> VOXY_MIXINS_NEEDING_IRIS = Set.of(
        "qouteall.imm_ptl.core.compat.mixin_voxy.MixinVoxyRenderPipelineFactory_OwnDimension"
    );

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (VOXY_MIXINS_NEEDING_IRIS.contains(mixinClassName) && !isLoaded("iris")) {
            return false;
        }
        for (Map.Entry<String, String> entry : MOD_BY_MIXIN_PACKAGE.entrySet()) {
            if (mixinClassName.startsWith(entry.getKey()) && !isLoaded(entry.getValue())) {
                return false;
            }
        }
        for (Map.Entry<String, String> entry : MOD_BY_PACKAGE.entrySet()) {
            if (targetClassName.startsWith(entry.getKey())) {
                return isLoaded(entry.getValue());
            }
        }
        return true;
    }

    private static boolean isLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
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
