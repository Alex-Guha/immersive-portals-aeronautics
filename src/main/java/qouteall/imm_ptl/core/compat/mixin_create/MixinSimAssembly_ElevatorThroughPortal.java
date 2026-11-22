/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AssemblyException;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals;

/**
 * Create Aeronautics' assembly refuses while an elevator pulley among the blocks has its car partly through a portal
 * ({@link IPElevatorPortals#whyNotMoved}): it would take the car apart and move its blocks with the ship, those past the
 * portal plane included. Checked once the blocks are found, before anything moves; the reason shows on the assembler.
 */
@Mixin(value = SimAssemblyHelper.class, remap = false)
public class MixinSimAssembly_ElevatorThroughPortal {
    @WrapOperation(
        method = "assembleFromSingleBlock",
        at = @At(value = "INVOKE",
            target = "Ldev/simulated_team/simulated/util/SimAssemblyHelper;disassembleAndAddCreateContraptions(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;Ljava/util/Collection;ZLjava/util/List;)V"),
        require = 1
    )
    private static void ip_notWithACarThroughAPortal(Level level, BoundingBox3ic bounds, Collection<BlockPos> blocks, boolean passGluesBack,
                                                     List<AABB> glues, Operation<Void> original) throws AssemblyException {
        if (level instanceof final ServerLevel serverLevel) {
            final Set<BlockPos> moved = Set.copyOf(blocks);
            final Component why = IPElevatorPortals.whyNotMoved(serverLevel, moved::contains);
            if (why != null) {
                throw new AssemblyException(why);
            }
        }
        original.call(level, bounds, blocks, passGluesBack, glues);
    }
}
