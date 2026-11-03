/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEdges;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;

import java.util.Map;
import java.util.Set;

/**
 * Neo Voxy's distant tracks (its Create compat; not in Roxy's Voxy) bake every edge of Create's client graph that is
 * not a turn and has both nodes in the player's dimension as a straight line of track blocks between the two nodes,
 * sampled every 0.4 blocks on the render thread. A portal edge within one dimension ({@link IPCreatePortalEdges}) passes that
 * test: it drew a track from one portal to the other, and with the far portal on a ship (its node in Sable's plot,
 * about 29 million blocks away) the bake took tens of millions of block positions and the client hung until it timed
 * out (2.8.10.1-neo-voxy). Portal edges, ours and Create's, are not track in the world.
 * <p>
 * On a server our set of portal edges reaches the client in a packet of its own, which can come a tick after Create's
 * graph: an edge with one node in Sable's plot space and the other outside it is never a track either, whatever the
 * set says.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.compat.create.DistantTrackRenderer", remap = false)
public class MixinVoxyDistantTrackRenderer_PortalEdges {
    @Inject(method = "collectStraight", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_skipPortalEdges(
        TrackNode node, TrackNode other, TrackEdge edge, Map<BlockPos, BlockState> out, Set<BlockPos> exclusion,
        CallbackInfo ci
    ) {
        if (edge.isInterDimensional() || ip_inPlot(node) != ip_inPlot(other)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean ip_inPlot(TrackNode node) {
        Vec3 position = node.getLocation().getLocation();
        return IPSableCompatHelper.isPlotSpacePosition(position.x, position.z);
    }
}
