/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * A single-block ship reads its light at its pose as it is drawn, from the ship's own level: for a far half those are
 * the destination's coordinates in the wrong world (below the nether's floor, say), so a far half reads the world it
 * is drawn in.
 */
@Mixin(value = VanillaSingleSubLevelRenderData.class, remap = false)
public class MixinSableSingleRenderData_FarHalfLight {
    @ModifyArg(method = "renderSingleBlock", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/SingleBlockSubLevelWrapper;setup(Lnet/minecraft/client/multiplayer/ClientLevel;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"),
            index = 0, require = 0)
    private ClientLevel ip_lightOfDrawnWorld(final ClientLevel level) {
        return IPStraddleRender.isProjecting() ? Minecraft.getInstance().level : level;
    }
}
