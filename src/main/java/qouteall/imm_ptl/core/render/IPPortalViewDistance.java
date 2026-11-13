/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * Portal view distance (client option {@code portalViewDistance}, feature request #11): a portal view shows the far
 * side only that many chunks past the portal and fades into the far world's fog there; terrain and LODs beyond are
 * not drawn.
 * <p>
 * A portal pass renders from a camera behind the far portal. Without scaling, that camera's distance to a point is
 * the distance along the line of sight through the portal, so the cut is one radius per pass: the farthest point of
 * the destination portal plus the option, measured like vanilla's terrain fog and Sodium's culler (cylindrical: the
 * larger of the horizontal distance and the height difference). It caps Sodium's search distance
 * ({@code MixinSodiumRenderSectionManager_PortalViewDistance}) and the pass's terrain fog
 * ({@code MixinRenderSystem_PortalViewFog}) and, for shader packs, Iris's {@code far} and {@code dhRenderDistance}
 * uniforms, which their border fog is drawn from ({@code MixinIris*_PortalViewDistance}); Distant Horizons skips
 * its LOD pass in a view with a cut
 * ({@code MixinDhIpBeforeRenderEvent_RenderInPortals}). Sodium's own fog occlusion would cull at the clamped fog
 * too, but Iris turns it off whenever a shader pack is loaded, the player may have turned it off, and it needs the
 * fog colour's alpha at exactly 1. A nested view inherits the outer cut, scaled by the inner portal, so the fog runs
 * on through both portals.
 * <p>
 * Approach ramp: as the camera comes within a few blocks of a portal, the cut moves out to the full render distance
 * and then, with Distant Horizons, the LOD fog distance out to DH's own (both eased), so the view through the portal
 * already looks like the world the player steps into and there is no pop when crossing. The ramp starts farther out
 * the faster the camera moves, so Sodium has time to mesh what comes into view.
 * <p>
 * A distant portal, whose reach lies past the render distance (a portal near the render distance, or a large option),
 * has no cut: the far world's terrain ends at the render distance by itself, and its terrain fog (clamped as with a
 * cut) and LODs end in fog at the reach instead (Iris's {@code dhRenderDistance}, or DH's own fog without a pack:
 * {@code IPDhPortalFog}), which the approach ramp moves out to the pass's own.
 * <p>
 * Not for fuse-view portals (no fog there), global portals (a dimension stack's floor and ceiling:
 * {@link IPPortalDrawDistance} draws them out to their own range) or mirrors. Plan and stages: {@code docs/lessons/portal-view-distance.md}.
 */
public final class IPPortalViewDistance {
    private static final double DH_FOG_PER_CUT = 0.8;
    /**
     * With a patched pack fog, Iris's {@code far} is the cut over this: Complementary dithers vanilla terrain out over
     * the last quarter of {@code far} with DH ({@code DH_BLENDING}: {@code exp(-3 (d/far)^16)}, 1 % discarded at 0.75),
     * so that band lies past the terrain's end, in the fog.
     */
    private static final double PATCHED_FAR_PER_CUT = 0.75;

    public static final String PACK_FOG_START_UNIFORM = "ipa_portalFogStart";
    public static final String PACK_FOG_END_UNIFORM = "ipa_portalFogEnd";
    /**
     * Whether the loaded shader pack's border fog is measured over a band before the cut
     * ({@code MixinIrisTransformPatcher_PortalViewFog}: Complementary).
     */
    public static volatile boolean packFogPatched = false;

    /** The cut (blocks) of each portal pass in progress, innermost last; NaN for a pass without one. */
    private static final DoubleArrayList cuts = new DoubleArrayList();
    /**
     * Per portal pass in progress, innermost last: in the last part of an approach ramp (no cut), how far the LOD fog
     * distance has moved from the full render distance to Distant Horizons' own, 0 to 1; NaN otherwise.
     */
    private static final DoubleArrayList lodFogs = new DoubleArrayList();
    /**
     * Per portal pass in progress, innermost last: where the LOD fog lies (blocks from the pass camera) when the
     * option's reach past the portal lies past the render distance (a distant portal), the start of that pass's
     * {@link #lodFogs} progress; NaN where it is the full render distance.
     */
    private static final DoubleArrayList lodReaches = new DoubleArrayList();

    /** The mode of the {@code FogRenderer.setupFog} last run; null after {@code setupNoFog}. */
    private static @Nullable FogRenderer.FogMode fogMode = null;

    // approach ramp: the ramp's length in blocks at least, the seconds of travel it spans at speed, and the camera speed
    private static final double RAMP_MIN_BLOCKS = 24;
    private static final double RAMP_SECONDS = 3;
    /**
     * How far a view's reach goes on past the full render distance, as a part of it, while Iris's
     * {@code dhRenderDistance} moves from where the cut left it to DH's own ({@link #capDhRenderDistanceBlocks}). A
     * approach ramp so ends without a jump in the pack's fog.
     */
    private static final double LOD_FOG_SPAN = 0.5;
    private static final double SPEED_SMOOTHING_SECONDS = 0.25;
    private static double cameraSpeed = 0;
    private static @Nullable Vec3 lastCameraPos = null;
    private static long lastCameraNanos = 0;
    private static int lastCameraFrame = -1;

    private IPPortalViewDistance() {
    }

    /**
     * Whether {@code portal}, seen inside the portal pass in progress, lies wholly past that pass's cut (the nearest
     * point of its box, measured like the cut): it would only show opaque fog, so its own pass is not rendered.
     */
    public static boolean isPastCut(Portal portal, Vec3 camera) {
        return isPastCut(portal.getThinBoundingBox(), camera);
    }

    /**
     * Whether an entity seen in the portal pass in progress lies wholly past its cut. Sodium already culls entities
     * in sections its search did not reach, but not those showing a name or glowing, nor any with its entity culling
     * off; a name tag is drawn without fog.
     */
    public static boolean isEntityPastCut(Entity entity) {
        return hasCut() && isPastCut(qouteall.imm_ptl.core.compat.sable_integration.IPPlotEntitiesClient.toWorld(
            entity, entity.getBoundingBoxForCulling()), PortalRendering.getRenderingCameraPos());
    }

    /** Whether a particle seen in the portal pass in progress lies wholly past its cut (particles are not culled). */
    public static boolean isParticlePastCut(AABB box) {
        return hasCut() && isPastCut(box, PortalRendering.getRenderingCameraPos());
    }

    /** Whether {@code box}'s nearest point lies past the cut in progress, measured like the cut. */
    private static boolean isPastCut(AABB box, Vec3 camera) {
        double cut = currentCut();
        if (Double.isNaN(cut)) {
            return false;
        }
        double dx = Math.max(0, Math.max(box.minX - camera.x, camera.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - camera.y, camera.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - camera.z, camera.z - box.maxZ));
        return Math.max(Math.sqrt(dx * dx + dz * dz), dy) > cut;
    }

    private static boolean applies(Portal portal) {
        return !portal.isFuseView() && !portal.isGlobalPortal && !(portal instanceof GlobalTrackedPortal)
            && !(portal instanceof Mirror);
    }

    /** Called with {@code portal} pushed as the innermost portal layer, right before its world is rendered. */
    public static void beginPass(Portal portal) {
        double cut = computeCut(portal);
        cuts.add(cut);
    }

    public static void endPass() {
        cuts.removeDouble(cuts.size() - 1);
        lodFogs.removeDouble(lodFogs.size() - 1);
        lodReaches.removeDouble(lodReaches.size() - 1);
    }

    private static double computeCut(Portal portal) {
        double full = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        double outer = currentCut();
        double outerLodFog = currentLodFog();
        double cut = Double.isNaN(outer) ? Double.NaN : outer * portal.getScale();
        double lodFog = Double.isNaN(outerLodFog) ? 1 : outerLodFog;
        double outerLodReach = currentLodReach();
        double lodReach = Double.isNaN(outerLodReach) ? Double.NaN : outerLodReach * portal.getScale();
        int chunks = IPConfig.getConfig().portalViewDistance;
        if (chunks > 0 && applies(portal)) {
            double reach = farthestPortalPoint(portal) + chunks * 16.0;
            double distance = distanceToPortal(portal);
            double ramp = rampProgress(distance);
            if (reach < full) {
                // past the full render distance the reach goes on as the LOD fog's progress towards DH's distance;
                // only the approach ramp takes it there, so walking away from a portal never thins the fog
                double own = reach;
                double lodFogSpan = full * LOD_FOG_SPAN;
                if (ramp > 0) {
                    own += Math.max(0, full + lodFogSpan - own) * smoothstep(ramp);
                }
                if (own < full) {
                    cut = Double.isNaN(cut) ? own : Math.min(cut, own);
                }
                else {
                    lodFog = Math.min(lodFog, (own - full) / lodFogSpan);
                }
            }
            else {
                // a distant portal, whose reach lies past the render distance: the far
                // world's terrain ends there by itself and its LODs end in fog at the reach, which moves out to DH's
                // own distance along the approach ramp. Dropping the cut alone left the view without its fog.
                lodReach = Double.isNaN(lodReach) ? reach : Math.min(lodReach, reach);
                lodFog = Math.min(lodFog, ramp);
            }
        }
        // a cut at or past the render distance would change nothing but still turn the LODs off
        if (cut >= full - 1e-3) {
            cut = Double.NaN;
        }
        boolean lodFogged = Double.isNaN(cut) && lodFog < 1;
        lodFogs.add(lodFogged ? lodFog : Double.NaN);
        lodReaches.add(lodFogged ? lodReach : Double.NaN);
        return cut;
    }

    /**
     * How far the camera has come along {@code portal}'s approach ramp, 0 to 1: 0 until it is a ramp's length from the
     * portal (at least {@link #RAMP_MIN_BLOCKS}, else {@link #RAMP_SECONDS} of travel at the camera's speed), 1 from a
     * quarter second of travel (at least a block) before it. Along it the cut moves out to the full render distance and
     * on through {@link #LOD_FOG_SPAN}, so the view matches the player's when they cross.
     */
    private static double rampProgress(double distance) {
        updateCameraSpeed();
        double end = Math.max(1, cameraSpeed * 0.25);
        double length = Math.max(RAMP_MIN_BLOCKS, cameraSpeed * RAMP_SECONDS);
        return Mth.clamp((end + length - distance) / length, 0, 1);
    }

    public static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * Distance from the camera on this side of the portal to its nearest point; in a nested pass, the distance along
     * the line of sight through the outer portals. Called with {@code portal} pushed as the innermost layer.
     */
    private static double distanceToPortal(Portal portal) {
        return portal.getDistanceToNearestPointInPortal(
            portal.inverseTransformPoint(PortalRendering.getRenderingCameraPos())
        );
    }

    /**
     * The player's camera speed (blocks per second), smoothed, sampled once a frame while portals are drawn. A jump
     * (a crossing, a teleport, a dimension change) is skipped and keeps the last speed; after a gap of more than the
     * smoothing time without portals the speed starts over.
     */
    private static void updateCameraSpeed() {
        if (lastCameraFrame == RenderStates.frameIndex) {
            return;
        }
        lastCameraFrame = RenderStates.frameIndex;
        Vec3 pos = RenderStates.originalCamera.getPosition();
        long now = System.nanoTime();
        double seconds = (now - lastCameraNanos) / 1e9;
        if (lastCameraPos != null && seconds > 0) {
            double moved = pos.distanceTo(lastCameraPos);
            if (seconds > SPEED_SMOOTHING_SECONDS) {
                cameraSpeed = 0;
            }
            else if (moved < 32) {
                double weight = Math.min(1, seconds / SPEED_SMOOTHING_SECONDS);
                cameraSpeed += (moved / seconds - cameraSpeed) * weight;
            }
        }
        lastCameraPos = pos;
        lastCameraNanos = now;
    }

    /** Cylindrical distance from the pass's camera to the farthest corner of the destination portal's box. */
    private static double farthestPortalPoint(Portal portal) {
        Vec3 camera = PortalRendering.getRenderingCameraPos();
        AABB box = portal.getThinBoundingBox();
        double farthest = 0;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = portal.transformPoint(new Vec3(
                (i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY,
                (i & 4) == 0 ? box.minZ : box.maxZ
            ));
            double dx = corner.x - camera.x;
            double dz = corner.z - camera.z;
            double distance = Math.max(Math.sqrt(dx * dx + dz * dz), Math.abs(corner.y - camera.y));
            farthest = Math.max(farthest, distance);
        }
        return farthest;
    }

    /** The cut of the innermost portal pass in progress, NaN outside portal passes or without a cut. */
    public static double currentCut() {
        return cuts.isEmpty() ? Double.NaN : cuts.getDouble(cuts.size() - 1);
    }

    /**
     * What the distance uniforms of the pass in progress depend on, NaN when they are the player's own: the cut, or
     * minus one minus an approach ramp's LOD fog progress (negative, so it never equals a cut).
     */
    public static double uniformKey() {
        double cut = currentCut();
        if (!Double.isNaN(cut)) {
            return cut;
        }
        double lodFog = currentLodFog();
        return Double.isNaN(lodFog) ? Double.NaN : -1 - lodFog - 2 * lodFogAnchor();
    }

    /**
     * In a pass without a cut whose LODs end in fog: how far that fog has moved from {@link #lodFogAnchor} to the LOD
     * distance's own, 0 to 1 (eased by the callers); NaN otherwise.
     */
    public static double currentLodFog() {
        return lodFogs.isEmpty() ? Double.NaN : lodFogs.getDouble(lodFogs.size() - 1);
    }

    private static double currentLodReach() {
        return lodReaches.isEmpty() ? Double.NaN : lodReaches.getDouble(lodReaches.size() - 1);
    }

    /** Where the LOD fog of the pass in progress lies before its {@link #currentLodFog} progress (blocks). */
    public static double lodFogAnchor() {
        double reach = currentLodReach();
        return Double.isNaN(reach) ? Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0 : reach;
    }

    public static boolean hasCut() {
        return !Double.isNaN(currentCut());
    }

    public static float capSearchDistance(float distance) {
        double cut = currentCut();
        return Double.isNaN(cut) ? distance : Math.min(distance, (float) cut + 0.5f);
    }

    /**
     * Iris's {@code far} in a pass with a cut: shader packs' border fog ends there. With the patched pack fog it lies a
     * little past the cut instead ({@link #PATCHED_FAR_PER_CUT}).
     */
    public static int capRenderDistanceBlocks(int blocks) {
        double cut = currentCut();
        if (Double.isNaN(cut)) {
            return blocks;
        }
        return packFogPatched ? (int) Math.ceil(cut / PATCHED_FAR_PER_CUT) : Math.min(blocks, (int) Math.ceil(cut));
    }

    /** The patched pack fog's band: as wide as the option past the portal, at least 16 blocks. */
    public static double packFogBand() {
        return Math.max(16, IPConfig.getConfig().portalViewDistance * 8.0);
    }

    /**
     * Where the patched pack fog starts (blocks from the pass camera), {@code packFogDistance} being the pack's own
     * fog distance in this pass. In a pass with a cut, a band before it. In the last part of an approach ramp the band
     * widens to the pack's own fog (start 0, end 1.25 of its distance, which the patch maps to itself), so the patch
     * fades out without a jump.
     */
    public static double packFogStart(double packFogDistance) {
        double cut = currentCut();
        if (!Double.isNaN(cut)) {
            return Math.max(0, cut - packFogBand());
        }
        double lodFog = currentLodFog();
        if (Double.isNaN(lodFog)) {
            return 0;
        }
        double anchor = lodFogAnchor();
        return Math.max(0, anchor - packFogBand()) * (1 - smoothstep(lodFog));
    }

    /** Where the patched pack fog is opaque, 0 when the pack's fog is its own ({@link #packFogStart}). */
    public static double packFogEnd(double packFogDistance) {
        double cut = currentCut();
        if (!Double.isNaN(cut)) {
            return cut;
        }
        double lodFog = currentLodFog();
        if (Double.isNaN(lodFog)) {
            return 0;
        }
        double anchor = lodFogAnchor();
        return anchor + (1.25 * packFogDistance - anchor) * smoothstep(lodFog);
    }

    /**
     * Iris's {@code dhRenderDistance} in a pass with a cut: 0.8 of the cut. With DH, Complementary fogs by this alone
     * but dithers vanilla terrain out between 0.8 and 1.0 of {@code far} (its {@code DH_BLENDING}) so LODs show
     * through; with {@code far} at the cut and no LODs there, the holes showed sky as static. At 0.8 its fog is at
     * 95 % where that band starts and opaque at the cut. BSL fogs at {@code max(far, dhRenderDistance)}, which stays
     * the cut. In the last part of an approach ramp it moves from 0.8 of the full render distance (where the cut
     * left it) to DH's own.
     */
    public static int capDhRenderDistanceBlocks(int blocks) {
        double cut = currentCut();
        if (!Double.isNaN(cut)) {
            return Math.min(blocks, (int) Math.ceil(cut * DH_FOG_PER_CUT));
        }
        double lodFog = currentLodFog();
        if (Double.isNaN(lodFog)) {
            return blocks;
        }
        double from = lodFogAnchor() * DH_FOG_PER_CUT;
        if (blocks <= from) {
            return blocks;
        }
        // geometric: packs' fog follows distance over the fog distance, so each step looks alike
        return (int) Math.ceil(from * Math.pow(blocks / from, smoothstep(lodFog)));
    }

    public static void onSetupFog(FogRenderer.FogMode mode) {
        fogMode = mode;
    }

    public static void onSetupNoFog() {
        fogMode = null;
    }

    /**
     * Where the terrain fog of the pass in progress ends: the cut, or the reach of a distant portal's view
     * ({@link #lodReaches}); NaN where it is not clamped. Past the render distance the terrain ends there by itself,
     * but Distant Horizons turns vanilla's fog off wherever it draws LODs, so without the clamp the far world's terrain
     * showed unfogged up to its end (at render distance 12: from 166 blocks).
     */
    private static double fogReach() {
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return Double.NaN;
        }
        double cut = currentCut();
        return Double.isNaN(cut) ? currentLodReach() : cut;
    }

    /** How far a distant portal's terrain fog has moved to the pass's own along the approach ramp, 0 with a cut. */
    private static float fogRelease() {
        return hasCut() ? 0 : (float) smoothstep(currentLodFog());
    }

    /** The fog fades over a quarter of the distance past the portal, at least 8 blocks. */
    public static double fadeBand() {
        return Math.max(8, IPConfig.getConfig().portalViewDistance * 4.0);
    }

    public static float clampFogStart(float start) {
        double reach = fogReach();
        if (Double.isNaN(reach)) {
            return start;
        }
        float ours = (float) Math.max(0, reach - fadeBand());
        return Math.min(start, ours + (start - ours) * fogRelease());
    }

    public static float clampFogEnd(float end) {
        double reach = fogReach();
        if (Double.isNaN(reach)) {
            return end;
        }
        float ours = (float) reach;
        return Math.min(end, ours + (end - ours) * fogRelease());
    }
}
