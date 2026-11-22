/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AssemblyException;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorFrames;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * The physics assembler refuses to disassemble a ship while an elevator pulley on it has its car partly through a portal
 * ({@link IPElevatorPortals#whyNotMoved}): the car's blocks past the portal plane would be placed behind the portal.
 * Its own checks run as disassembly starts and again just before the blocks are placed; the reason shows on it.
 */
@Mixin(value = PhysicsAssemblerBlockEntity.class, remap = false)
public class MixinPhysicsAssembler_ElevatorThroughPortal {
    @Inject(method = "throwDisassemblyExceptions", at = @At("HEAD"), require = 1)
    private void ip_notWithACarThroughAPortal(ServerSubLevel ship, CallbackInfo ci) throws AssemblyException {
        if (ship.getLevel() instanceof final ServerLevel level) {
            final Component why = IPElevatorPortals.whyNotMoved(level,
                pos -> ship.getUniqueId().equals(IPElevatorFrames.shipAt(level, pos)));
            if (why != null) {
                throw new AssemblyException(why);
            }
        }
    }
}
