/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPRapierPipelineContraptions;

/**
 * A Create elevator car on a ship whose shaft runs through a portal, and an elevator's far copy on a ship, get no
 * physics collider (docs/plans/elevators.md): a contraption on a ship pushes its ship, and the part of the car past the
 * portal plane is inside whatever lies behind the portal in its own level (the ground under a ship whose portal is near
 * it), so the ship was thrown into the air.
 */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public class MixinSableRapierPipeline_ElevatorCars implements IPRapierPipelineContraptions {
    @Unique
    private static final org.slf4j.Logger ip_LOGGER = com.mojang.logging.LogUtils.getLogger();
    @Unique
    private static final boolean ip_CREATE = ModList.get().isLoaded("create");

    @Shadow
    @Final
    private Object2ObjectMap<KinematicContraption, ?> activeContraptions;

    @WrapOperation(
        method = "add(Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;)V",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;sable$shouldCollide()Z"),
        require = 1
    )
    private boolean ip_noColliderThroughPortal(KinematicContraption contraption, Operation<Boolean> original) {
        if (ip_CREATE && qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortals.hasNoCollider(contraption)) {
            ip_LOGGER.info("[IPElevator] contraption {} on a ship, through a portal: no physics collider",
                ((net.minecraft.world.entity.Entity) contraption).getId());
            return false;
        }
        return original.call(contraption);
    }

    @Override
    public boolean ip_hasContraption(final KinematicContraption contraption) {
        return activeContraptions.containsKey(contraption);
    }
}
