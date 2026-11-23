/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.List;

/**
 * The portals between an outer pass and the pass drawn now, for drawing an outer world's translucents in front of a
 * portal inside the views behind it (IPNearTranslucents, IPStraddleRender.forEachNearShip). Layer {@code j} is the world
 * of pass {@code j} (0: the player's); its portal is the one at stack index {@code j}, which pass {@code j} looks through,
 * and the chain from it runs through the portals of layers {@code j + 1} up to the pass drawn now.
 */
public final class IPPortalChain {
    private IPPortalChain() {
    }

    /** @return the portals from layer {@code j}'s world to the world drawn now, in order */
    public static List<Portal> from(int j) {
        List<Portal> layers = PortalRendering.getPortalLayersView();
        return layers.subList(j, layers.size());
    }

    /** @return the portal that pass {@code j} looks through to the next layer */
    public static Portal portalOf(int j) {
        return PortalRendering.getPortalLayersView().get(j);
    }

    public static Vec3 mapPoint(List<Portal> chain, Vec3 point) {
        for (Portal portal : chain) {
            point = portal.transformPoint(point);
        }
        return point;
    }

    /** A displacement through the chain, scaled by the portals' scales. */
    public static Vec3 mapVector(List<Portal> chain, Vec3 vector) {
        for (Portal portal : chain) {
            vector = portal.transformLocalVec(vector);
        }
        return vector;
    }

    /** The point in layer {@code j}'s world that the chain from {@code j} takes to {@code point}. */
    public static Vec3 unmapPoint(List<Portal> chain, Vec3 point) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            point = chain.get(i).inverseTransformPoint(point);
        }
        return point;
    }

    private static Plane mapPlane(List<Portal> chain, Plane plane) {
        Vec3 pos = plane.pos();
        Vec3 normal = plane.normal();
        for (Portal portal : chain) {
            pos = portal.transformPoint(pos);
            normal = portal.transformLocalVecNonScale(normal);
        }
        return new Plane(pos, normal);
    }

    /** @return whether the chain reflects (a mirror in it turns faces around) */
    public static boolean hasMirror(List<Portal> chain) {
        for (Portal portal : chain) {
            if (portal instanceof Mirror) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the camera's side of the portal pass {@code j} looks through, in the world drawn now (what of layer
     * {@code j} lies in front of that portal); null when that portal has no inner clip
     */
    public static @Nullable Plane nearPlane(int j) {
        List<Portal> layers = PortalRendering.getPortalLayersView();
        Plane inner = layers.get(j).getInnerClipping();
        if (inner == null) {
            return null;
        }
        return mapPlane(layers.subList(j + 1, layers.size()), new Plane(inner.pos(), inner.normal().scale(-1)));
    }

    /**
     * @return what pass {@code j} kept of its world (beyond the portal it was drawn for), in the world drawn now; null
     * for the player's world (layer 0), which nothing cuts
     */
    public static @Nullable Plane outerPlane(int j) {
        if (j == 0) {
            return null;
        }
        List<Portal> layers = PortalRendering.getPortalLayersView();
        Plane inner = layers.get(j - 1).getInnerClipping();
        if (inner == null) {
            return NO_PLANE;
        }
        return mapPlane(layers.subList(j, layers.size()), inner);
    }

    /** Returned by {@link #outerPlane} when the pass's portal has no inner clip: skip that layer. */
    public static final Plane NO_PLANE = new Plane(Vec3.ZERO, Vec3.ZERO);
}
