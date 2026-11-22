/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A client asking for an elevator car's floor list finds the car in any dimension. Create looks it up in the player's
 * own dimension only: a player who logged in on the far side of an elevator through a portal (the car on the pulley's
 * side, seen through the portal) never got the car's floors, and its controls said "No Floors" once they rode it back.
 * Entity ids are unique across the server's dimensions.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.elevator.ElevatorFloorListPacket$RequestFloorList", remap = false)
public class MixinRequestFloorList_AnyDimension {
    @WrapOperation(
        method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntity(I)Lnet/minecraft/world/entity/Entity;"),
        require = 1
    )
    private Entity ip_anyDimension(Level level, int id, Operation<Entity> original, ServerPlayer sender) {
        final Entity own = original.call(level, id);
        if (isElevator(own)) {
            return own;
        }
        for (final ServerLevel other : sender.server.getAllLevels()) {
            if (other != level) {
                final Entity there = other.getEntity(id);
                if (isElevator(there)) {
                    return there;
                }
            }
        }
        return own;
    }

    private static boolean isElevator(final Entity entity) {
        return entity instanceof AbstractContraptionEntity ace && ace.getContraption() instanceof ElevatorContraption;
    }
}
