/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.neoforged.fml.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.platform_specific.IPAeronauticsClientEntry;

import java.util.List;

/**
 * Mod Menu (NeoForge Edition) lists only {@code ip_aeronautics} of our jar's five mod ids, as NeoForge's Mods list does
 * ({@code MixinModListScreen}).
 * <p>
 * Mod Menu builds its mods (list entries, the mod count on the Mods button) once, in its constructor, from
 * {@code ModList.getSortedMods()}. Its {@code [modproperties.modmenu]} table is read per jar, not per mod id, so
 * {@code neoforge.mods.toml} cannot make the four Immersive Portals ids children of ours.
 */
@Mixin(targets = "com.terraformersmc.mod_menu.ModMenu", remap = false)
public class MixinModMenu_OneEntry {
    @ModifyExpressionValue(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/ModList;getSortedMods()Ljava/util/List;")
    )
    private List<ModContainer> ip_hideAliasIds(List<ModContainer> mods) {
        return mods.stream().filter(mod -> !IPAeronauticsClientEntry.IP_ALIAS_IDS.contains(mod.getModId())).toList();
    }
}
