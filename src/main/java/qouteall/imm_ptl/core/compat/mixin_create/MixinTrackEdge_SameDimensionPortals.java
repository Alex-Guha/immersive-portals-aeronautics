/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEdges;

/**
 * The edge between the two portal faces of a pair linked within one dimension is a portal edge, as Create makes it
 * between dimensions: zero length, any angle, no intersections, travelling points fire the portal listener on it
 * ({@link IPCreatePortalEdges}). The flag is saved with Create's graph and read back with it.
 */
@Mixin(value = TrackEdge.class, remap = false)
public class MixinTrackEdge_SameDimensionPortals {
    @Shadow
    public TrackNode node1;
    @Shadow
    public TrackNode node2;
    @Shadow
    BezierConnection turn;

    /** {@link IPCreatePortalEdges#version()} the cached answer was taken at. */
    @Unique
    private int ip_linkVersion = -1;
    @Unique
    private boolean ip_link;

    @ModifyReturnValue(method = "isInterDimensional", at = @At("RETURN"))
    private boolean ip_sameDimensionPortal(final boolean interDimensional) {
        return interDimensional || ip_isLink();
    }

    @Unique
    private boolean ip_isLink() {
        if (turn != null || node1 == null || node2 == null) {
            return false;
        }
        final int version = IPCreatePortalEdges.version();
        if (ip_linkVersion != version) {
            ip_link = IPCreatePortalEdges.isLink(node1.getLocation(), node2.getLocation());
            ip_linkVersion = version;
        }
        return ip_link;
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void ip_saveLink(final DimensionPalette dimensions, final CallbackInfoReturnable<CompoundTag> cir) {
        if (ip_isLink()) {
            cir.getReturnValue().putBoolean(IPCreatePortalEdges.SAVE_KEY, true);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void ip_readLink(final TrackNode node1, final TrackNode node2, final CompoundTag tag, final TrackGraph graph,
                                    final DimensionPalette dimensions, final CallbackInfoReturnable<TrackEdge> cir) {
        if (tag.getBoolean(IPCreatePortalEdges.SAVE_KEY) && node1 != null && node2 != null) {
            IPCreatePortalEdges.add(node1.getLocation(), node2.getLocation());
        }
    }
}
