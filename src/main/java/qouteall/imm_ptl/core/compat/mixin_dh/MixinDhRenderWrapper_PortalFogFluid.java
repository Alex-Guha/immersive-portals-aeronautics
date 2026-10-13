/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_neoforge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * DH keeps vanilla fog on (and forces its own LOD fog pass on) while the fog state is "special":
 * camera in a fluid, blindness/darkness, or a fluid at the main camera's block whose id is not
 * {@code minecraft:water}, {@code minecraft:lava} or empty. In a portal pass the main camera is the
 * portal camera, behind the destination portal and often inside terrain, and {@code Minecraft.level}
 * is the destination world, so a lavafall there ({@code minecraft:flowing_lava}) turned vanilla fog
 * and DH's fog pass on for a column of player positions near the portal. IP already reports no
 * camera fluid in portal passes ({@code MixinCamera}); the block lookup now agrees.
 */
@Mixin(value = MinecraftRenderWrapper_neoforge.class, remap = false)
public class MixinDhRenderWrapper_PortalFogFluid {
    @Redirect(
        method = "isFogStateSpecial",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
        ),
        require = 0
    )
    private FluidState ip_noCameraBlockFluidInPortalPass(ClientLevel level, BlockPos pos) {
        if (PortalRendering.isRendering()) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return level.getFluidState(pos);
    }
}
