/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import java.lang.ref.WeakReference;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Client side of {@link IPCreateCarriageCrossing}: a carriage copy crossing an IP portal is drawn whole and cut exactly
 * at the portal plane, the way straddling ships are (docs/lessons/straddle-rendering.md), instead of Create's whole-block
 * cut with its purple glass seam.
 * <ul>
 *     <li>Its Flywheel parts (block structure, block entity and actor visuals, and the bogeys, which Create draws
 *     outside the carriage's embedding and {@code MixinCarriageContraptionVisual_BogeyEmbedding} moves into one of
 *     their own) get a per-embedding plane through {@link IPFlywheelStraddleClip} ({@code gl_ClipDistance[1]}).</li>
 *     <li>Parts Create draws with its vanilla renderer (block entities without a Flywheel visual, actors such as train
 *     controls and blaze burners) are drawn by the copy on whose side their block's centre lies
 *     ({@link #drawsVanillaPart}).</li>
 * </ul>
 * Create splits a carriage only once one of its outer wheel points has crossed, so its ends reach through the portal
 * with no copy on the other side: the nose before the split, the tail after it. Such a copy is cut at the plane all
 * the same, and the part past the plane is drawn in the other dimension by its own dimension's Flywheel engine, seen
 * through the portal ({@link IPFlywheelFarHalf}, as for ships). A copy of the same carriage the server has already
 * created on the other side while this client still has the carriage unsplit (the client's carriage lags the server's)
 * is hidden meanwhile: it would stand still at its spawn position.
 */
public final class IPCreateCarriageCrossingClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Seen through a portal a copy is cut this much past the plane (on the camera's side), so it reaches IP's own clip
     * whichever band that has. Seen directly it is cut exactly at the plane: in portal passes IP clips Flywheel and
     * entities exactly at the far portal (no band, {@code MixinFlywheelRenderDispatcher_PortalClipState}), so the 1 cm
     * the ship cut keeps clear of the plane showed as a seam between the two halves (1.24.7).
     */
    private static final double SEAM = FrontClipping.ADJUSTMENT;
    private static final float[] REJECT = {0f, 0f, 0f, -1f};

    /** The carriage copy whose bogeys are being placed on this thread ({@code translateBogey} has no entity). */
    private static final ThreadLocal<CarriageContraptionEntity> BOGEY_COPY = new ThreadLocal<>();
    /** Flywheel embeddings of carriages (the carriage's own and its bogeys'), by embedding. Flywheel's worker threads add them. */
    private static final Map<EmbeddedEnvironment, WeakReference<CarriageContraptionEntity>> EMBEDDINGS =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());
    /** Copies reaching through a portal with no copy on the other side: their far halves are drawn there. */
    private static final Map<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> FAR_HALVES = new WeakHashMap<>();
    /** Copies placed by {@link #follow} (their dimension holds no wheel point of the carriage). */
    private static final Map<CarriageContraptionEntity, Boolean> FOLLOWING = new WeakHashMap<>();
    private static final Map<CarriageContraptionEntity, Boolean> STALE_LOGGED = new WeakHashMap<>();
    private static final Map<CarriageContraptionEntity, Long> OUTDATED_LOGGED = new WeakHashMap<>();
    private static final Map<CarriageContraptionEntity, Boolean> VANILLA_LOGGED = new WeakHashMap<>();
    private static final Map<CarriageContraptionEntity, Long> CUT_LOGGED = new WeakHashMap<>();
    /** How many ticks each copy has been placed (aligned) on this client. */
    private static final Map<CarriageContraptionEntity, Integer> ALIGNED_TICKS = new WeakHashMap<>();
    /** The carriage axis must meet the portal plane at least this steeply (cosine) to count as going through. */
    private static final double MIN_AXIS_DOT = 0.3;
    private static boolean logged;

    private IPCreateCarriageCrossingClient() {
    }

    /** After a copy was aligned for this tick: hands its crossing to the rendering. */
    public static void onAligned(final CarriageContraptionEntity entity, @Nullable IPCreateCarriageCrossing.Crossing crossing) {
        final Contraption contraption = entity.getContraption();
        if (!(contraption instanceof final IPCarriageCrossingHolder holder)) {
            return;
        }
        final boolean live = entity.getCarriage() != null
            && IPCreateCarriageCrossing.hasPointIn(entity.getCarriage(), IPCreateTrainSides.keyOf(entity));
        if (!live && !IPCreateCarriageCrossing.STOCK) {
            // no wheel point in this dimension: Create's anchors here are stale; follow() places and cuts the copy
            FAR_HALVES.remove(entity);
            ALIGNED_TICKS.remove(entity);
            return;
        }
        if (crossing == null && !IPCreateCarriageCrossing.STOCK) {
            crossing = reachingThrough(entity);
        }
        // the far half is drawn while no placed copy covers it: the carriage is not split (its ends), or the other
        // dimension's copy has not been placed for two ticks yet (a first placed tick is drawn standing still)
        ALIGNED_TICKS.merge(entity, 1, (a, b) -> Math.min(a + b, 1000));
        if (crossing != null && live && (crossing.farHalf() || !otherCopyReady(entity, crossing))) {
            FAR_HALVES.put(entity, crossing);
        } else {
            FAR_HALVES.remove(entity);
        }
        holder.ip_setCrossing(crossing);
        refreshRendered(entity);
        if (crossing == null && contraption instanceof final CarriageContraption carriageContraption
            && !carriageContraption.notInPortal() && !usesCreateCut(contraption)) {
            final long key = ((long) carriageContraption.portalCutoffMin << 32) ^ (carriageContraption.portalCutoffMax & 0xffffffffL);
            final Long previousKey = CUT_LOGGED.put(entity, key);
            if (previousKey == null || previousKey != key) {
                // the case that showed purple glass: Create cuts a placed copy and no crossing of ours applies
                LOGGER.debug("[IPTrainCrossing] carriage {} in {} has Create's cut {}..{} but no crossing (drawn whole): {}",
                    entity.getId(), entity.level().dimension().location(), carriageContraption.portalCutoffMin,
                    carriageContraption.portalCutoffMax, describe(entity));
            }
        }
        if (!logged && crossing != null) {
            logged = true;
            LOGGER.debug("[IPTrainCrossing] carriage {} crossing a portal in {}: drawn whole, cut at the portal plane",
                entity.getId(), entity.level().dimension().location());
        }
    }

    /**
     * Rebuilds a copy's rendered blocks (and its seam particle slice) if what they should show differs from what they
     * were built for: the whole carriage (a crossing of ours, or Create not cutting it), or Create's cut with its
     * cutoffs. Create applies a dimension record's last cutoffs when it binds a new copy, which after a few crossings
     * are those of an earlier one. Also replaces Create's own rebuild requests ({@code DimensionalCarriageEntity
     * .invalidate}), which come with every cutoff step while crossing. What a model was built for is noted when it is
     * built ({@link #onStructureBuilt}), and every frame checks it ({@link #rebuildIfOutdated}).
     */
    public static void refreshRendered(final CarriageContraptionEntity entity) {
        entity.updateRenderedPortalCutoff();
        rebuildIfOutdated(entity);
    }

    /** Every frame (Flywheel's {@code ContraptionVisual.beginFrame}) and on changes: rebuild if outdated. */
    public static void rebuildIfOutdated(final AbstractContraptionEntity entity) {
        if (IPCreateCarriageCrossing.STOCK || !(entity.getContraption() instanceof final CarriageContraption contraption)
            || !(contraption instanceof final IPCarriageCrossingHolder holder)) {
            return;
        }
        if (renderedKey(contraption) != holder.ip_getRenderedKey()) {
            contraption.invalidateClientContraptionStructure();
            contraption.invalidateClientContraptionChildren();
        }
    }

    /**
     * Whether a copy's model was built for something other than what it should show now (Flywheel rebuilds it in the
     * visual's {@code beginFrame}, which it skips for visuals out of view, while far-half and portal draws may still
     * draw its instances). Such a model is not drawn; a rebuild is requested.
     */
    private static boolean isOutdated(final CarriageContraptionEntity entity, final boolean farHalf) {
        if (IPCreateCarriageCrossing.STOCK || !(entity.getContraption() instanceof final CarriageContraption contraption)
            || !(contraption instanceof final IPCarriageCrossingHolder holder) || holder.ip_getRenderedKey() == Long.MAX_VALUE) {
            return false;
        }
        final long key = renderedKey(contraption);
        if (key == holder.ip_getRenderedKey()) {
            return false;
        }
        contraption.invalidateClientContraptionStructure();
        contraption.invalidateClientContraptionChildren();
        if (OUTDATED_LOGGED.put(entity, key) == null) {
            LOGGER.debug("[IPTrainCrossing] carriage {} in {}: model built for {} but should show {}, not drawn until rebuilt ({}): {}",
                entity.getId(), entity.level().dimension().location(), keyText(holder.ip_getRenderedKey()), keyText(key),
                farHalf ? "far half" : PortalRendering.isRendering() ? "portal view" : "direct view", describe(entity));
        }
        return true;
    }

    private static String keyText(final long key) {
        return key == Long.MIN_VALUE ? "the whole carriage" : "Create's cut " + (int) (key >> 32) + ".." + (int) key;
    }

    /**
     * Whether Create's vanilla drawing of a carriage's block structure or bogeys is skipped: with Flywheel running it
     * only happens in a pass of another level than the entity's ({@code supportsVisualization} compares with
     * {@code Minecraft.level}), as a duplicate of its Flywheel visual. Logged once per carriage.
     */
    public static boolean skipsVanillaStructure(@Nullable final net.minecraft.world.entity.Entity entity, final String what) {
        if (!(entity instanceof final CarriageContraptionEntity carriage) || IPCreateCarriageCrossing.STOCK
            || !dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn()) {
            return false;
        }
        if (VANILLA_LOGGED.put(carriage, Boolean.TRUE) == null) {
            final net.minecraft.client.multiplayer.ClientLevel current = Minecraft.getInstance().level;
            LOGGER.debug("[IPTrainCrossing] carriage {} of {} reached Create's vanilla {} drawing while drawing {} ({}); skipped: {}",
                carriage.getId(), carriage.level().dimension().location(), what,
                current != null ? current.dimension().location() : null,
                PortalRendering.isRendering() ? "portal view" : "direct view", describe(carriage));
        }
        return true;
    }

    /** Start of a frame: request rebuilds of every known carriage copy whose model is outdated. */
    public static void onFrameStart() {
        if (EMBEDDINGS.isEmpty()) {
            return;
        }
        for (final WeakReference<CarriageContraptionEntity> reference : embeddingOwners()) {
            final CarriageContraptionEntity entity = reference.get();
            if (entity != null && !entity.isRemoved()) {
                rebuildIfOutdated(entity);
            }
        }
    }

    /** A carriage's model is being built ({@code ContraptionVisual.setupStructure}): note what for. */
    public static void onStructureBuilt(@Nullable final Contraption contraption) {
        if (!(contraption instanceof final CarriageContraption carriageContraption)
            || !(contraption instanceof final IPCarriageCrossingHolder holder)) {
            return;
        }
        final long key = renderedKey(carriageContraption);
        holder.ip_setRenderedKey(key);
        if (key != Long.MIN_VALUE && !IPCreateCarriageCrossing.STOCK
            && carriageContraption.entity instanceof final CarriageContraptionEntity entity) {
            // Create's own cut (whole-block, purple glass seam) is built: say why ours does not apply
            LOGGER.debug("[IPTrainCrossing] carriage {} in {} built with Create's cut {}..{}: {}", entity.getId(),
                entity.level().dimension().location(), carriageContraption.portalCutoffMin,
                carriageContraption.portalCutoffMax, describe(entity));
        }
    }

    private static long renderedKey(final CarriageContraption contraption) {
        // on the client Create's cut applies only with stockTrains (MixinCarriageContraption_SeamlessCrossing)
        return !usesCreateCut(contraption)
            || contraption instanceof final IPCarriageCrossingHolder holder && holder.ip_getCrossing() != null
            || contraption.notInPortal()
            ? Long.MIN_VALUE
            : ((long) contraption.portalCutoffMin << 32) ^ (contraption.portalCutoffMax & 0xffffffffL);
    }

    /**
     * Whether Create's own whole-block cut (and purple glass) applies to a copy on the client: only with
     * {@code -Dip_aeronautics.stockTrains=true}. Otherwise copies are drawn whole, cut at the plane by a crossing of
     * ours, at portals into another dimension and within one alike.
     */
    public static boolean usesCreateCut(final Contraption contraption) {
        return IPCreateCarriageCrossing.STOCK;
    }

    public static void beginBogey(@Nullable final Entity entity) {
        BOGEY_COPY.set(entity instanceof final CarriageContraptionEntity copy ? copy : null);
    }

    public static void endBogey() {
        BOGEY_COPY.remove();
    }

    /**
     * An angle of a bogey as the copy placing it shows it: Create's (the bogey's real heading, kept current by
     * {@code MixinCarriageBogey_TrainSides}) when the bogey's wheels are on this copy's side of a portal, else
     * {@code alongCopy}, the copy's own heading, as Create does for a bogey in another dimension. A copy's part past its
     * portal is placed straight on through it, so a bogey there (or one split by the portal) runs along the copy; with
     * its real heading it showed turned by the portal's rotation (90 degrees between perpendicular portals).
     */
    public static float bogeyAngle(final CarriageBogey bogey, final float createAngle, final float alongCopy) {
        final CarriageContraptionEntity copy = BOGEY_COPY.get();
        final Carriage carriage = copy == null ? null : copy.getCarriage();
        // a carriage with one copy, or derailed (Create's angle then carries its derail tilt), is Create's
        if (carriage == null || IPCreateCarriageCrossing.STOCK || carriage.train.derailed
            || ((qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor) carriage).ip_getEntities().size() < 2) {
            return createAngle;
        }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> side =
            IPCreateTrainSides.bogeySide(bogey, bogey.getDimension());
        return side != null && side.equals(IPCreateTrainSides.keyOf(copy)) ? createAngle : alongCopy;
    }

    @Nullable
    public static IPCreateCarriageCrossing.Crossing crossingOf(@Nullable final Entity entity) {
        if (entity instanceof final AbstractContraptionEntity contraptionEntity
            && contraptionEntity.getContraption() instanceof final IPCarriageCrossingHolder holder) {
            return holder.ip_getCrossing();
        }
        return null;
    }

    /** Remembers a Flywheel embedding drawing (part of) a carriage. */
    public static void registerEmbedding(@Nullable final VisualEmbedding embedding, @Nullable final Entity entity) {
        if (embedding instanceof final EmbeddedEnvironment environment && entity instanceof final CarriageContraptionEntity carriage) {
            EMBEDDINGS.put(environment, new WeakReference<>(carriage));
        }
    }

    /**
     * @param root the environment's root embedding (block entity visuals may nest embeddings)
     * @return the cut of a carriage embedding for this Flywheel pass, relative to the engine's render origin, or null
     * if the environment draws no crossing carriage
     */
    static float @Nullable [] flywheelPlane(final EmbeddedEnvironment environment, final EmbeddedEnvironment root,
                                            final Vec3i renderOrigin) {
        if (EMBEDDINGS.isEmpty()) {
            return null;
        }
        WeakReference<CarriageContraptionEntity> reference = EMBEDDINGS.get(environment);
        if (reference == null) {
            reference = EMBEDDINGS.get(root);
        }
        final CarriageContraptionEntity entity = reference != null ? reference.get() : null;
        final float[] plane = flywheelPlane(entity, renderOrigin);
        IPCreateCrossingTrace.plane(entity, plane);
        return plane;
    }

    private static float @Nullable [] flywheelPlane(@Nullable final CarriageContraptionEntity entity, final Vec3i renderOrigin) {
        if (entity != null && (isHidden(entity) || isOutdated(entity, false))) {
            return REJECT;
        }
        final IPCreateCarriageCrossing.Crossing crossing = crossingOf(entity);
        if (crossing == null) {
            return null;
        }
        if (facesPortalClipAway(crossing.keepPoint(), crossing.keepNormal())) {
            return REJECT;
        }
        final Vec3 n = crossing.keepNormal();
        final Vec3 rel = crossing.keepPoint().subtract(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        final double w = -n.dot(rel) + (PortalRendering.isRendering() ? SEAM : 0.0);
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) w};
    }

    private static List<WeakReference<CarriageContraptionEntity>> embeddingOwners() {
        synchronized (EMBEDDINGS) {
            return List.copyOf(EMBEDDINGS.values());
        }
    }

    /** Every carriage copy with a Flywheel visual on this client (trace). */
    static List<CarriageContraptionEntity> knownCopies() {
        final java.util.Set<CarriageContraptionEntity> copies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (final WeakReference<CarriageContraptionEntity> reference : embeddingOwners()) {
            final CarriageContraptionEntity entity = reference.get();
            if (entity != null) {
                copies.add(entity);
            }
        }
        return List.copyOf(copies);
    }

    /** A copy's state for the trace. */
    static String traceState(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        final StringBuilder text = new StringBuilder();
        text.append("wheels ").append(carriage == null ? "?" : IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity)) ? "Y" : "N")
            .append(" aligned ").append(ALIGNED_TICKS.getOrDefault(entity, 0))
            .append(" valid ").append(entity.validForRender ? "Y" : "N")
            .append(" firstPos ").append(entity.firstPositionUpdate ? "Y" : "N")
            .append(FOLLOWING.containsKey(entity) ? " following" : "")
            .append(isStaleCopy(entity) ? " STALE" : "");
        if (carriage != null) {
            final Carriage.DimensionalCarriageEntity dce = IPCreateTrainSides.existingRecordOf(carriage, entity);
            text.append(" key ").append(IPCreateTrainSides.keyOf(entity).location().getPath())
                .append(" init ").append(dce == null ? "no record" : dce.pointsInitialised ? "Y" : "N");
        }
        final IPCreateCarriageCrossing.Crossing farHalf = FAR_HALVES.get(entity);
        if (farHalf != null) {
            text.append(" drawsFarHalfInto ").append(farHalf.otherKey().location().getPath());
        }
        if (!IPCreateCarriageCrossing.STOCK && isHidden(entity)) {
            text.append(" HIDDEN(").append(carriage == null ? "no carriage" : !entity.validForRender ? "not valid"
                : !IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity)) ? "no wheels"
                : isStaleCopy(entity) ? "stale" : "covered by far half").append(')');
        }
        final IPCreateCarriageCrossing.Crossing crossing = crossingOf(entity);
        if (crossing != null) {
            text.append(" crossing ").append(crossing.farHalf() ? "reaching" : "split").append(" portal ").append(crossing.portal().getId())
                .append(String.format(" depth %.3f", -entity.position().subtract(crossing.keepPoint()).dot(crossing.keepNormal())));
        }
        return text.toString();
    }

    /**
     * Block entities of a crossing copy that Create draws with their vanilla renderer: those whose block centre lies
     * past the portal plane are left out (the other dimension's copy draws them).
     */
    public static BitSet withoutBeyondPlane(final BitSet shouldRender, final List<BlockEntity> blockEntities,
                                            @Nullable final AbstractContraptionEntity entity, final BitSet scratch) {
        if (!(entity instanceof CarriageContraptionEntity) || shouldRender.isEmpty() || IPCreateCarriageCrossing.STOCK) {
            return shouldRender;
        }
        BitSet result = shouldRender;
        for (int i = shouldRender.nextSetBit(0); i >= 0 && i < blockEntities.size(); i = shouldRender.nextSetBit(i + 1)) {
            if (!drawsVanillaPart(entity, blockEntities.get(i).getBlockPos())) {
                if (result == shouldRender) {
                    scratch.clear();
                    scratch.or(shouldRender);
                    result = scratch;
                }
                result.clear(i);
            }
        }
        return result;
    }

    /**
     * The crossing of a copy that is not split but reaches through an IP portal: the carriage's axis (through its
     * rotation anchors) goes through the portal's opening and its bounding box reaches past the plane. Kept is the side
     * of the carriage's middle. At a portal within the copy's dimension, the copy past it will have the side key of the
     * pair's face there.
     */
    @Nullable
    private static IPCreateCarriageCrossing.Crossing reachingThrough(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null || !IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity))) {
            return null;
        }
        final Carriage.DimensionalCarriageEntity dce = IPCreateTrainSides.existingRecordOf(carriage, entity);
        final Vec3 first = dce.rotationAnchors.getFirst();
        final Vec3 second = dce.rotationAnchors.getSecond();
        if (first == null || second == null || !IPCreateCarriageCrossing.isFinite(first)
            || !IPCreateCarriageCrossing.isFinite(second) || first.distanceToSqr(second) < 1.0E-6) {
            return null;
        }
        final Vec3 middle = first.add(second).scale(0.5);
        final Vec3 axis = first.subtract(second).normalize();
        final AABB box = entity.getBoundingBox();
        final List<Portal> portals = IPPortalLookup.touching(entity.level(), box.inflate(0.5),
            p -> !(p instanceof Mirror) && p.getTeleportable() && Math.abs(p.getScale() - 1.0) < 1.0E-6);
        Portal best = null;
        Vec3 bestHit = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Portal portal : portals) {
            final Vec3 normal = portal.getNormal();
            final double along = axis.dot(normal);
            if (Math.abs(along) < MIN_AXIS_DOT) {
                continue;
            }
            final double distance = portal.getDistanceToPlane(middle);
            final Vec3 hit = middle.subtract(axis.scale(distance / along));
            if (portal.getDistanceToNearestPointInPortal(hit) > 0.5) {
                continue;
            }
            // a copy straddling the plane by its bounding box only: some corner lies on the other side
            final double sign = Math.signum(distance) == 0.0 ? 1.0 : Math.signum(distance);
            if (!reachesPast(box, portal.getOriginPos(), normal.scale(sign))) {
                continue;
            }
            if (Math.abs(distance) < bestDistance) {
                best = portal;
                bestHit = hit;
                bestDistance = Math.abs(distance);
            }
        }
        if (best == null) {
            return null;
        }
        final double sign = best.getDistanceToPlane(middle) < 0.0 ? -1.0 : 1.0;
        return new IPCreateCarriageCrossing.Crossing(best, best.getOriginPos(), best.getNormal().scale(sign), true,
            otherKeyThrough(best, bestHit));
    }

    /**
     * The key a copy past this portal has: its destination dimension, or for a portal within one dimension the side
     * key of the portal-edge face nearest where the carriage's axis comes out (the axis runs at bogey height, the face
     * at track level half a block from the plane).
     */
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> otherKeyThrough(
        final Portal portal, final Vec3 hit) {
        if (!portal.getDestDim().equals(portal.level().dimension())) {
            return portal.getDestDim();
        }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> face =
            IPCreateTrainSides.faceKeyNear(portal.getDestDim(), portal.transformPoint(hit), 3.0);
        return face != null ? face : portal.getDestDim();
    }

    private static boolean reachesPast(final AABB box, final Vec3 point, final Vec3 keepNormal) {
        for (int corner = 0; corner < 8; corner++) {
            final Vec3 c = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                (corner & 4) == 0 ? box.minZ : box.maxZ);
            if (c.subtract(point).dot(keepNormal) < 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a copy must not be drawn: it has not been placed by this client yet (no carriage bound, or no tick
     * yet: it stands at its spawn position, the server's, ahead of this client's carriage; Create's vanilla renderer
     * waits for {@code validForRender} too, Flywheel does not), none of its carriage's wheel points is in its dimension
     * (a copy the server created before this client's carriage reached it, or one Create keeps a few ticks after the
     * carriage left: either way it is not placed and stands still), or another copy draws the far half into its
     * dimension while this one is not ready.
     */
    private static boolean isHidden(final CarriageContraptionEntity entity) {
        if (IPCreateCarriageCrossing.STOCK) {
            return false;
        }
        final Carriage carriage = entity.getCarriage();
        if (carriage == null || !entity.validForRender) {
            return true;
        }
        if (!IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity)) || isStaleCopy(entity)) {
            return true;
        }
        return isCoveredByFarHalf(entity);
    }

    /**
     * Places a copy whose dimension holds none of its carriage's wheel points (Create has no current anchors for it)
     * where the copy that has them is, mapped through the portal between them. Such a copy is not drawn, but a player
     * may already ride it: the server hands riders over when its own carriage splits, a little before this client's
     * does, and the copy then still stood at the server's spawn position (the camera jumped ahead and back).
     *
     * @return whether the copy was placed
     */
    public static boolean follow(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        if (IPCreateCarriageCrossing.STOCK || carriage == null) {
            return false;
        }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension = IPCreateTrainSides.keyOf(entity);
        FOLLOWING.remove(entity);
        if (IPCreateCarriageCrossing.hasPointIn(carriage, dimension)) {
            return false;
        }
        for (final Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Carriage.DimensionalCarriageEntity> entry :
            ((qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor) carriage).ip_getEntities().entrySet()) {
            final Carriage.DimensionalCarriageEntity leader = entry.getValue();
            if (entry.getKey().equals(dimension) || leader == null || !IPCreateCarriageCrossing.hasPointIn(carriage, entry.getKey())) {
                continue;
            }
            final CarriageContraptionEntity leaderEntity = leader.entity.get();
            final IPCreateCarriageCrossing.Crossing crossing = crossingOf(leaderEntity);
            final Vec3 anchor = leader.positionAnchor;
            final Vec3 first = leader.rotationAnchors.getFirst();
            final Vec3 second = leader.rotationAnchors.getSecond();
            if (leaderEntity == null || leaderEntity.isRemoved() || crossing == null
                || !crossing.otherKey().equals(dimension) || anchor == null || first == null || second == null
                || !IPCreateCarriageCrossing.isFinite(anchor) || !IPCreateCarriageCrossing.isFinite(first)
                || !IPCreateCarriageCrossing.isFinite(second)) {
                continue;
            }
            final Portal portal = crossing.portal();
            final Vec3 position = portal.transformPoint(anchor.add(IPCreateCarriageCrossing.shift(leaderEntity, leader)));
            final Vec3 a = portal.transformPoint(first);
            final Vec3 b = portal.transformPoint(second);
            final double dx = a.x - b.x;
            final double dy = a.y - b.y;
            final double dz = a.z - b.z;
            // as Create's alignEntity does from the anchors
            entity.prevYaw = entity.yaw;
            entity.prevPitch = entity.pitch;
            entity.setPos(position);
            entity.yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * 180.0 / Math.PI) + 180.0F;
            entity.pitch = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI) * -1.0F;
            if (entity.firstPositionUpdate) {
                entity.xo = entity.getX();
                entity.yo = entity.getY();
                entity.zo = entity.getZ();
                entity.prevYaw = entity.yaw;
                entity.prevPitch = entity.pitch;
            }
            FOLLOWING.put(entity, Boolean.TRUE);
            // its cut: this dimension's portal back to the leader, keeping the side away from the leader's part
            final Vec3 keepPoint = portal.transformPoint(crossing.keepPoint());
            final Portal back = portalBack(entity.level(), keepPoint, IPCreateTrainSides.realDimension(entry.getKey()));
            if (entity.getContraption() instanceof final IPCarriageCrossingHolder holder) {
                holder.ip_setCrossing(back == null ? null : new IPCreateCarriageCrossing.Crossing(back, keepPoint,
                    portal.transformLocalVecNonScale(crossing.keepNormal()).scale(-1.0), false, entry.getKey()));
                refreshRendered(entity);
            }
            return true;
        }
        return false;
    }

    @Nullable
    private static Portal portalBack(final net.minecraft.world.level.Level level, final Vec3 point,
                                     final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> leaderDim) {
        Portal best = null;
        double bestDistance = 1.0;
        for (final Portal candidate : IPPortalLookup.touching(level, new AABB(point, point).inflate(1.5),
            p -> !(p instanceof Mirror) && p.getTeleportable() && leaderDim.equals(p.getDestDim()))) {
            final double distance = candidate.getDistanceToNearestPointInPortal(point);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Whether a copy stands where it belongs: its dimension holds a wheel point, or it follows the copy that does. */
    private static boolean isPlaced(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        return carriage == null || IPCreateCarriageCrossing.STOCK
            || (!isStaleCopy(entity)
            && (IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity)) || FOLLOWING.containsKey(entity)));
    }

    /**
     * Whether Create's record of this copy's dimension refers to another entity: a second client copy of the carriage
     * in the same dimension, which Create no longer updates (its cut and position are left from an earlier crossing).
     */
    private static boolean isStaleCopy(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return false;
        }
        final Carriage.DimensionalCarriageEntity own = IPCreateTrainSides.existingRecordOf(carriage, entity);
        final CarriageContraptionEntity current = own != null ? own.entity.get() : null;
        final boolean stale = current != null && current != entity && !current.isRemoved();
        if (stale && STALE_LOGGED.put(entity, Boolean.TRUE) == null) {
            LOGGER.debug("[IPTrainCrossing] carriage copy {} in {} is not the one Create updates ({}): not drawn",
                entity.getId(), entity.level().dimension().location(), current.getId());
        }
        return stale;
    }

    /** Whether the copy on the far side of this copy's cut is placed (and so draws what lies past the plane). */
    private static boolean otherCopyPlaced(final CarriageContraptionEntity entity, final IPCreateCarriageCrossing.Crossing crossing) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return false;
        }
        final Carriage.DimensionalCarriageEntity other =
            ((qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor) carriage).ip_getEntities().get(crossing.otherKey());
        final CarriageContraptionEntity copy = other != null ? other.entity.get() : null;
        return copy != null && copy != entity && !copy.isRemoved() && isPlaced(copy);
    }

    /**
     * Whether a part of a copy that Create draws with its vanilla renderer (block entities without a Flywheel visual,
     * actors such as train controls and blaze burners) is drawn: never by a copy that is not placed; a part past the
     * portal plane only if no placed copy on the other side draws it (it then lies behind this side's portal, as
     * with Create alone).
     */
    public static boolean drawsVanillaPart(@Nullable final Entity entity, final net.minecraft.core.BlockPos localPos) {
        if (!(entity instanceof final CarriageContraptionEntity carriageEntity) || IPCreateCarriageCrossing.STOCK) {
            return true;
        }
        final boolean drawn = drawsVanillaPartOf(carriageEntity, localPos);
        IPCreateCrossingTrace.vanilla(carriageEntity, drawn);
        return drawn;
    }

    private static boolean drawsVanillaPartOf(final CarriageContraptionEntity carriageEntity, final net.minecraft.core.BlockPos localPos) {
        if (!isPlaced(carriageEntity)) {
            return false;
        }
        final IPCreateCarriageCrossing.Crossing crossing = crossingOf(carriageEntity);
        if (crossing == null) {
            return true;
        }
        final float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        final Vec3 world = carriageEntity.toGlobalVector(Vec3.atCenterOf(localPos), partialTick);
        return !crossing.isBeyond(world) || !otherCopyPlaced(carriageEntity, crossing);
    }

    /** The carriage's per-dimension state, for the log. */
    private static String describe(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return "no carriage";
        }
        final StringBuilder text = new StringBuilder();
        final Carriage.DimensionalCarriageEntity own = IPCreateTrainSides.existingRecordOf(carriage, entity);
        if (own != null) {
            IPCreateCarriageCrossing.of(entity, own);
            text.append("crossing: ").append(IPCreateCarriageCrossing.lastFailure()).append("; ");
        }
        final com.simibubi.create.content.trains.entity.Train current =
            com.simibubi.create.Create.RAILWAYS.sided(entity.level()).trains.get(carriage.train.id);
        text.append("train object current: ").append(current == carriage.train)
            .append(", carriage in it: ").append(current != null && current.carriages.contains(carriage)).append("; ");
        text.append("wheels here: ").append(IPCreateCarriageCrossing.hasPointIn(carriage, IPCreateTrainSides.keyOf(entity)));
        text.append("; following: ").append(FOLLOWING.containsKey(entity));
        for (final Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Carriage.DimensionalCarriageEntity> entry :
            ((qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor) carriage).ip_getEntities().entrySet()) {
            final Carriage.DimensionalCarriageEntity dce = entry.getValue();
            if (dce == null) {
                continue;
            }
            final CarriageContraptionEntity copy = dce.entity.get();
            text.append("; ").append(entry.getKey().location()).append(": cutoff ").append(dce.cutoff)
                .append(" node ").append(dce.pivot != null ? dce.pivot.getLocation() : null)
                .append(" copy ").append(copy == null ? "none" : copy == entity ? "this" : copy.getId() + (copy.isRemoved() ? " (removed)" : ""))
                .append(" initialised ").append(dce.pointsInitialised);
        }
        return text.toString();
    }

    /** Whether the other dimension's copy of a split carriage has been placed for at least two ticks. */
    private static boolean otherCopyReady(final CarriageContraptionEntity entity, final IPCreateCarriageCrossing.Crossing crossing) {
        final Carriage carriage = entity.getCarriage();
        if (carriage == null) {
            return false;
        }
        final Carriage.DimensionalCarriageEntity other =
            ((qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor) carriage).ip_getEntities().get(crossing.otherKey());
        final CarriageContraptionEntity copy = other != null ? other.entity.get() : null;
        return copy != null && copy != entity && !copy.isRemoved() && copy.validForRender && ALIGNED_TICKS.getOrDefault(copy, 0) >= 2;
    }

    /**
     * Whether another copy of this copy's carriage draws its far half into this copy's dimension (or portal side).
     */
    private static boolean isCoveredByFarHalf(final CarriageContraptionEntity entity) {
        if (FAR_HALVES.isEmpty() || entity.getCarriage() == null) {
            return false;
        }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key = IPCreateTrainSides.keyOf(entity);
        for (final Map.Entry<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> entry : FAR_HALVES.entrySet()) {
            final CarriageContraptionEntity other = entry.getKey();
            if (other != entity && !other.isRemoved() && other.getCarriage() == entity.getCarriage()
                && entry.getValue().otherKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * After a level's Flywheel pass: draws the parts of carriages that reach into this level through a portal (from
     * another dimension, or from elsewhere in this one), with their own level's engine and the camera mapped back
     * through that portal; one draw per portal.
     */
    public static void drawFarHalves(final RenderContext context) {
        if (FAR_HALVES.isEmpty()) {
            return;
        }
        final ClientLevel level = context.level();
        final Map<Portal, Map<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing>> byPortal = new java.util.IdentityHashMap<>();
        for (final Map.Entry<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> entry : FAR_HALVES.entrySet()) {
            final CarriageContraptionEntity entity = entry.getKey();
            final Portal portal = entry.getValue().portal();
            if (entity.isRemoved() || portal.isRemoved() || !(entity.level() instanceof ClientLevel)
                || !portal.getDestDim().equals(level.dimension())) {
                continue;
            }
            byPortal.computeIfAbsent(portal, p -> new java.util.IdentityHashMap<>()).put(entity, entry.getValue());
        }
        for (final Map.Entry<Portal, Map<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing>> group : byPortal.entrySet()) {
            final Portal portal = group.getKey();
            final Map<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> carriages = group.getValue();
            if (!(portal.level() instanceof final ClientLevel source)) {
                continue;
            }
            final Quaternionf rotation = portal.getRotationD().toMcQuaternion();
            final Vec3 camera = portal.inverseTransformPoint(context.camera().getPosition());
            final IPFlywheelStraddleClip.FarHalf selection = (sourceLevel, environment, root, renderOrigin) -> {
                final CarriageContraptionEntity owner = ownerOf(environment, root);
                final IPCreateCarriageCrossing.Crossing crossing = owner != null ? carriages.get(owner) : null;
                final float[] plane = crossing != null && !isOutdated(owner, true) ? farHalfPlane(crossing, renderOrigin) : null;
                if (plane != null) {
                    IPCreateCrossingTrace.plane(owner, plane);
                }
                return plane;
            };
            if (!IPFlywheelFarHalf.draw(context, source, rotation, camera, selection)) {
                return;
            }
        }
    }

    @Nullable
    private static CarriageContraptionEntity ownerOf(final EmbeddedEnvironment environment, final EmbeddedEnvironment root) {
        WeakReference<CarriageContraptionEntity> reference = EMBEDDINGS.get(environment);
        if (reference == null) {
            reference = EMBEDDINGS.get(root);
        }
        return reference != null ? reference.get() : null;
    }

    /** The cut of a copy's far half: keeps the part past the plane, in the copy's own dimension. */
    private static float[] farHalfPlane(final IPCreateCarriageCrossing.Crossing crossing, final Vec3i renderOrigin) {
        final Portal portal = crossing.portal();
        final Vec3 n = crossing.keepNormal().scale(-1.0);
        if (facesPortalClipAway(portal.transformPoint(crossing.keepPoint()), portal.transformLocalVecNonScale(n))) {
            return REJECT;
        }
        final Vec3 rel = crossing.keepPoint().subtract(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        final double w = -n.dot(rel) + (PortalRendering.isRendering() ? SEAM : 0.0);
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) w};
    }

    /** Whether, in the current portal pass, IP's clip plane is the plane (point, keep normal) facing the other way. */
    private static boolean facesPortalClipAway(final Vec3 point, final Vec3 keepNormal) {
        if (!PortalRendering.isRendering()) {
            return false;
        }
        final Plane clip = PortalRendering.getActiveClippingPlane();
        return clip != null
            && clip.normal().dot(keepNormal) < -0.999
            && Math.abs(clip.normal().dot(point.subtract(clip.pos()))) < 0.05;
    }
}
