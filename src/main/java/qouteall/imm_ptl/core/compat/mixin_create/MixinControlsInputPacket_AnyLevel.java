/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControls;

/**
 * Server: a driver's input names the carriage copy they steer, which may be past a portal in another dimension; Create
 * looked it up in the driver's level only ({@link IPCreateTrainControls#findControlledEntity}).
 */
@Mixin(value = ControlsInputPacket.class, remap = false)
public class MixinControlsInputPacket_AnyLevel {
    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntity(I)Lnet/minecraft/world/entity/Entity;"),
        require = 0, expect = 0
    )
    private Entity ip_findInAnyLevel(final Level level, final int id, final Operation<Entity> original, final ServerPlayer player) {
        final Entity entity = original.call(level, id);
        return entity != null ? entity : IPCreateTrainControls.findControlledEntity(player, id);
    }
}
