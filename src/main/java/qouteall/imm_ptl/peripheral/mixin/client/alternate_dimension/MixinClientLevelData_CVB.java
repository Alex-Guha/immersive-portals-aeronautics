/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.mixin.client.alternate_dimension;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.ClientLevelData.class)
public class MixinClientLevelData_CVB {
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;getHorizonHeight(Lnet/minecraft/world/level/LevelHeightAccessor;)D",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGetSkyDarknessHeight(LevelHeightAccessor levelArg, CallbackInfoReturnable<Double> cir) {
        // Immersive Aeronautics: back with the alternate dimensions; the level whose sky is drawn (the argument)
        // decides, Minecraft.level only as a fallback
        ClientLevel world = levelArg instanceof ClientLevel clientLevel ? clientLevel : Minecraft.getInstance().level;
        if (world == null) {
            return;
        }
        boolean isAlternateDimension =
            AlternateDimensions.isAlternateDimension(world);

        if (isAlternateDimension) {
            cir.setReturnValue(-10000.0);
        }
    }
}
