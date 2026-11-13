/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectorTravelGuard;

@Mixin(ServerPlayer.class)
public class MixinServerPlayerEntity_MA {
    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void onChangeDimensionByVanilla(
        DimensionTransition dimensionTransition, CallbackInfoReturnable<Entity> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;
        // Immersive Aeronautics: another mod's teleport of a player crossing a dimension stack's floor or ceiling
        // (the Aether's fall to the overworld) does not happen, and must not reach the bookkeeping below
        if (VerticalConnectorTravelGuard.blocksTravel(this_, dimensionTransition.newLevel().dimension())) {
            cir.setReturnValue(null);
            return;
        }
        onBeforeDimensionTravel(this_);
    }
    
    // update chunk visibility data
    @Inject(method = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"))
    private void onTeleported(
        ServerLevel targetWorld,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        CallbackInfo ci
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;
        
        if (this_.level() != targetWorld) {
            onBeforeDimensionTravel(this_);
        }
    }
    
    private static void onBeforeDimensionTravel(ServerPlayer player) {
        CustomPortalGenManager customPortalGenManager =
            IPPerServerInfo.of(player.server).customPortalGenManager;
        
        if (customPortalGenManager != null) {
            customPortalGenManager.onBeforeConventionalDimensionChange(player);
            ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
            
            ServerTaskList.of(player.server).addTask(() -> {
                customPortalGenManager.onAfterConventionalDimensionChange(player);
                return true;
            });
        }
    }
}
