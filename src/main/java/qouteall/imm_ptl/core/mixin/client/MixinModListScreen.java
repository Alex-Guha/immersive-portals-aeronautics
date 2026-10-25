/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ModListScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;

/**
 * NeoForge's Mods list shows one entry per mod id, and our jar declares five. The four Immersive Portals ids stay
 * declared, since other mods detect and depend on them, but only {@code ip_aeronautics} is listed, with the config
 * screen behind its Config button.
 */
@Mixin(value = ModListScreen.class, remap = false)
public class MixinModListScreen {
    private static final Set<String> IP_ALIAS_IDS =
        Set.of("immersive_portals_core", "immersive_portals", "imm_ptl", "q_misc_util");

    @ModifyExpressionValue(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/ModList;getSortedMods()Ljava/util/List;")
    )
    private List<ModContainer> ip_hideAliasIds(List<ModContainer> mods) {
        return mods.stream().filter(mod -> !IP_ALIAS_IDS.contains(mod.getModId())).toList();
    }
}
