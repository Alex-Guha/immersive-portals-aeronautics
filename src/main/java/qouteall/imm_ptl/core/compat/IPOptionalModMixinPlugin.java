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
 */
public class IPOptionalModMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String, String> MOD_BY_PACKAGE = Map.of(
        "com.seibel.distanthorizons.", "distanthorizons",
        "net.diebuddies.", "physicsmod",
        "com.anton.shieldgenerators.", "shield_generators",
        "net.irisshaders.", "iris",
        "team.lodestar.lodestone.", "lodestone"
    );

    @Override
    public void onLoad(String mixinPackage) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        for (Map.Entry<String, String> entry : MOD_BY_PACKAGE.entrySet()) {
            if (targetClassName.startsWith(entry.getKey())) {
                return LoadingModList.get().getModFileById(entry.getValue()) != null;
            }
        }
        return true;
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
