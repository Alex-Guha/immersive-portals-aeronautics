/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/**
 * Private parts of Sable's per-level physics loop ({@code SubLevelPhysicsSystem.tickPipelinePhysics}) that
 * {@code IPFusedPhysicsStep} runs itself, for all levels at once.
 */
@Mixin(
    value = SubLevelPhysicsSystem.class,
    remap = false
)
public interface IPSubLevelPhysicsSystemAccessor {
    @Accessor("currentSubstep")
    void ip_setCurrentSubstep(int substep);

    @Accessor("queuedWakeUps")
    Collection<ArbitraryPhysicsObject> ip_getQueuedWakeUps();

    @Invoker("updateAllPoses")
    void ip_updateAllPoses(ServerSubLevelContainer container);
}
