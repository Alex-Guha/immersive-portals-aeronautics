/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorFloorListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Client: an elevator car's floor list finds its car in any dimension. Create looks it up in the player's own world
 * only, so the far copy of a car through a portal (made while the player is still on the pulley's side) never got its
 * floors, and its controls showed none once the player had crossed. Tried in order: the player's world, the world the
 * packet was sent for (IP switches {@code Minecraft.level} to it), every other loaded world.
 */
@Mixin(value = ElevatorFloorListPacket.class, remap = false)
public class MixinElevatorFloorListPacket_Portals {
    @WrapOperation(
        method = "handle(Lnet/minecraft/client/player/LocalPlayer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;")
    )
    private Entity ip$anyDimension(ClientLevel level, int id, Operation<Entity> original) {
        final Entity own = original.call(level, id);
        if (isElevator(own)) {
            return own;
        }
        final ClientLevel packetLevel = Minecraft.getInstance().level;
        if (packetLevel != null && packetLevel != level && isElevator(packetLevel.getEntity(id))) {
            return packetLevel.getEntity(id);
        }
        for (final ClientLevel other : ClientWorldLoader.getClientWorlds()) {
            if (other != level && isElevator(other.getEntity(id))) {
                return other.getEntity(id);
            }
        }
        return own;
    }

    private static boolean isElevator(Entity entity) {
        return entity instanceof AbstractContraptionEntity ace && ace.getContraption() instanceof ElevatorContraption;
    }
}
