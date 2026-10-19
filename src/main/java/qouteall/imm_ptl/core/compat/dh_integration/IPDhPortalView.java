/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * Distant Horizons inside portal views.
 * <p>
 * DH skips its whole pass (LODs and its clouds) whenever Immersive Portals reports a portal pass, so
 * a portal view showed terrain only out to the vanilla render distance and an empty sky, and the
 * far terrain and clouds popped in on crossing. The {@code mixin_dh} mixins let the pass run inside
 * portal views (DH already loads the viewed level on demand from the level renderer's world and
 * renders from the portal camera). What DH cannot know is <em>where</em> in another dimension to
 * load LODs: its loader ticks each level around the player's own coordinates. This class publishes,
 * once per frame from the portals rendered in the previous frame, the camera position mapped into
 * each dimension seen through a portal; the loader is pointed at it for every dimension other than
 * the player's. The last known position is kept so a level that drops out of view does not start
 * loading around meaningless coordinates.
 * <p>
 * No DH types here: this class is loaded whether or not DH is present.
 */
public final class IPDhPortalView {
    /** Master switch for DH rendering inside portal views. */
    public static volatile boolean ENABLED = true;

    private static volatile Map<ResourceKey<Level>, Vec3> VIEW_POSITIONS = Map.of();

    private IPDhPortalView() {
    }

    /**
     * Called before the portals of a frame are rendered, with the camera eye position and dimension.
     * Walks the portal chains rendered in the previous frame (outermost first) and records, per
     * destination dimension, the camera position transformed through the chain.
     */
    public static void updateFromLastFrame(Vec3 cameraPos, ResourceKey<Level> cameraDim) {
        Map<ResourceKey<Level>, Vec3> result = new HashMap<>(VIEW_POSITIONS);
        for (List<WeakReference<Portal>> chain : RenderStates.lastPortalRenderInfos) {
            Vec3 pos = cameraPos;
            ResourceKey<Level> dim = cameraDim;
            boolean intact = true;
            for (WeakReference<Portal> ref : chain) {
                Portal portal = ref.get();
                if (portal == null || portal.isRemoved()) {
                    intact = false;
                    break;
                }
                pos = portal.transformPoint(pos);
                dim = portal.getDestDim();
            }
            if (intact && !dim.equals(cameraDim)) {
                // the outermost portal into a dimension wins; it is the one the player is nearest to
                result.put(dim, pos);
            }
        }
        VIEW_POSITIONS = result;
    }

    private static Boolean dhLoaded = null;

    public static boolean isDhLoaded() {
        if (dhLoaded == null) {
            dhLoaded = ModList.get() != null && ModList.get().isLoaded("distanthorizons");
        }
        return dhLoaded;
    }

    /** The position in {@code dim} the player last looked at through a portal, or null if never. */
    public static @Nullable Vec3 viewPositionIn(ResourceKey<Level> dim) {
        return VIEW_POSITIONS.get(dim);
    }
}
