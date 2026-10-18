/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.peripheral.mixin.common.nether_portal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;

/**
 * Mods that light nether portals themselves call {@link PortalShape#createPortalBlocks()}, which IP's fire hook
 * ({@code MixinAbstractFireBlock_CVB}) never sees: Netherstorm lights obsidian frames from a flint-and-steel
 * {@code RightClickBlock} handler and cancels the click, so players got a vanilla portal. Vanilla itself only calls it
 * from {@code BaseFireBlock.onPlace}, which IP already redirects outside vanilla mode.
 */
@Mixin(PortalShape.class)
public class MixinPortalShape_IntrinsicPortal {
    @Shadow
    @Final
    private LevelAccessor level;

    @Shadow
    private BlockPos bottomLeft;

    @Inject(method = "createPortalBlocks", at = @At("HEAD"), cancellable = true)
    private void ip_lightImmersivePortalInstead(CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && bottomLeft != null
            && IntrinsicPortalGeneration.onVanillaPortalBlocksRequested(serverLevel, bottomLeft)
        ) {
            ci.cancel();
        }
    }
}
