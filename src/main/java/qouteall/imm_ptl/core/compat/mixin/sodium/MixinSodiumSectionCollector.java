/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

/**
 * IP's outer culling in the player's own view: a terrain section wholly hidden by the portal nearest the camera (behind
 * its plane and inside the cone from the camera through its opening, {@code FrustumCuller.canOuterCullWorldBox}) is
 * left out of Sodium's draw lists, since the portal's view paints over it.
 * <p>
 * Applied where Sodium's section search hands a visited section to its collector, not in the search's frustum test
 * ({@code MixinSodiumViewport}): a section the search may not enter is a section it cannot go through either, and
 * the face-by-face search can need a path through the hidden cone to reach sections beside the portal. The section's
 * pending rebuild is still queued, so its mesh is current when the portal stops hiding it.
 */
@Mixin(value = SectionCollector.class, remap = false)
public class MixinSodiumSectionCollector {
    // half the box Sodium's frustum tests a section by: 8, plus 1.125 for models sticking out (MixinSodiumViewport)
    @Unique
    private static final double SECTION_HALF_SIZE = 9.125;

    @ModifyVariable(
        method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private int ip_dropSectionHiddenByPortal(int flags, RenderSection section, int originalFlags) {
        if (flags == 0) {
            return 0;
        }
        FrustumCuller culler = SodiumInterface.frustumCuller;
        if (culler == null || culler.getOuterCulling() == null) {
            return flags;
        }
        double x = section.getCenterX();
        double y = section.getCenterY();
        double z = section.getCenterZ();
        if (culler.canOuterCullWorldBox(
            x - SECTION_HALF_SIZE, y - SECTION_HALF_SIZE, z - SECTION_HALF_SIZE,
            x + SECTION_HALF_SIZE, y + SECTION_HALF_SIZE, z + SECTION_HALF_SIZE
        )) {
            return 0;
        }
        return flags;
    }
}
