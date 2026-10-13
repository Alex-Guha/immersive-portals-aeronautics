/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.mixin_create.IPPhysicsStaffBeamAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPPhysicsStaffClientHandlerAccessor;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddlePick;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;
import qouteall.q_misc_util.my_util.DQuaternion;
import qouteall.q_misc_util.my_util.Plane;
import qouteall.q_misc_util.my_util.RayTraceResult;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Create Aeronautics (Simulated) physics staff across portals, client side (docs/lessons/physics-staff.md).
 * <ul>
 *     <li>The staff's pick also finds ships behind a portal and far halves of ships straddling a portal ({@link #pick});
 *     the server is told which portals the ship was seen through ({@code IPPhysicsStaffPortals.RemoteCallables.grab}).</li>
 *     <li>While dragging, the client works in the player's world: distance, orientation and goal are as the player sees
 *     the ship, and {@link Drag#frame} maps the player's world into the ship's. It changes when the player goes through
 *     a portal ({@link #onPlayerTeleported}) or the server moves the ship to another dimension
 *     ({@link RemoteCallables#onShipMoved}), whereupon the drag continues on the moved ship.</li>
 *     <li>The local player's beam and particles are drawn where the ship is seen.</li>
 * </ul>
 */
public final class IPPhysicsStaffPortalsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVER = "qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortals.RemoteCallables.";
    /** Sable's removal of a moved ship reaches the client before the server's message that the drag follows it. */
    private static final int MOVE_MESSAGE_GRACE_TICKS = 10;
    /** How long a drag waits for its moved ship to appear in the destination world. */
    private static final int MOVED_SHIP_TIMEOUT_TICKS = 100;

    /**
     * A ship block picked across a portal or on a far half.
     *
     * @param frame maps the player's world into the ship's
     * @param chain the portals the ship is seen through, for the server
     */
    private record Picked(ClientSubLevel ship, BlockHitResult hit, IPPortalIsometry frame, CompoundTag chain) {
    }

    /**
     * @param picked the ship picked across a portal, or null when {@code hit} is the staff's own pick (or a miss)
     */
    private record PickResult(HitResult hit, @Nullable Picked picked) {
    }

    /** The local player's drag. */
    private static final class Drag {
        PhysicsStaffClientHandler.ClientDragSession session;
        final UUID ship;
        ResourceKey<Level> playerDim;
        /** Maps the player's world ({@link #playerDim}) into the world of {@link #session}'s ship. */
        IPPortalIsometry frame;
        /** The dimension the ship was moved to, until it shows up there. */
        @Nullable
        ResourceKey<Level> movedTo;
        /** The portal map of that move, applied to {@link #frame} when the drag goes on with the moved ship. */
        @Nullable
        IPPortalIsometry pendingMove;
        BlockPos shift = BlockPos.ZERO;
        int waited;
        int removedFor;
        /** The drag cannot go on: Simulated stops it at its next tick. */
        boolean lost;

        Drag(final PhysicsStaffClientHandler.ClientDragSession session, final UUID ship, final ResourceKey<Level> playerDim,
             final IPPortalIsometry frame) {
            this.session = session;
            this.ship = ship;
            this.playerDim = playerDim;
            this.frame = frame;
        }
    }

    /** The target of the staff's current use, from its pick to the end of {@code onItemUsed}. */
    @Nullable
    private static Picked picked;
    /** The target of the local player's last staff use when across a portal (its lock beam). */
    @Nullable
    private static Picked lastPicked;
    @Nullable
    private static Drag drag;
    /** The hover box's pick, once per frame ({@link #hoverPick}). */
    @Nullable
    private static PickResult hover;
    private static int hoverFrame = -1;
    /** Where the local player's beam starts, from the last pass rendering the player's world. */
    @Nullable
    private static Vec3 beamStart;

    private IPPhysicsStaffPortalsClient() {
    }

    // ---- picking

    /**
     * In place of the staff's pick ({@code onItemUsed}): a ship behind a portal or a far half wins when nearer than
     * what the staff's own pick ({@code own}) found; what is behind a portal in the player's own world cannot be picked.
     */
    public static HitResult pick(final LocalPlayer player, final HitResult own) {
        final PickResult result = pickFor(player, own, 1.0f);
        picked = result.picked();
        return result.hit();
    }

    /**
     * In place of the pick of Simulated's hover box, once per frame (the box is updated in every pass): as {@link #pick}.
     */
    public static HitResult hoverPick(final LocalPlayer player, final float partialTick, final Supplier<HitResult> own) {
        if (hover == null || hoverFrame != RenderStates.frameIndex) {
            hover = pickFor(player, own.get(), partialTick);
            hoverFrame = RenderStates.frameIndex;
        }
        return hover.hit();
    }

    /**
     * @param level the world being rendered
     * @param own   the ship Simulated finds at the hover hit in {@code level}
     * @return the ship to put the hover box on in {@code level}: a ship picked across a portal only in a pass rendering
     * its own world; Simulated's own pick only in the player's world
     */
    @Nullable
    public static SubLevel hoverShip(final Level level, @Nullable final SubLevel own) {
        final PickResult result = hover;
        if (result != null && result.picked() != null) {
            return isShown(result.picked().ship(), level) ? result.picked().ship() : null;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.level() != level ? null : own;
    }

    private static PickResult pickFor(final LocalPlayer player, final HitResult own, final float partialTick) {
        if (!(player.level() instanceof final ClientLevel level)) {
            return new PickResult(own, null);
        }
        final Vec3 eye = player.getEyePosition(partialTick);
        final Vec3 end = eye.add(player.getViewVector(partialTick).scale(PhysicsStaffItem.RANGE));
        // IP's placeholder blocks fill nether portals with a thin outline at the plane: not a target
        final boolean ownMissed = own.getType() == HitResult.Type.MISS || (own instanceof final BlockHitResult block
            && level.getBlockState(block.getBlockPos()).getBlock() == PortalPlaceholderBlock.instance);
        double limit = ownMissed ? Double.POSITIVE_INFINITY : eye.distanceTo(IPShipBlockInteraction.toWorld(level, own.getLocation()));
        Picked best = null;

        final IPStraddlePick.Hit farHalf = IPStraddlePick.nearest(level, eye, end, partialTick, false);
        if (farHalf != null && farHalf.distance() < limit) {
            best = picked(farHalf, IPPortalIsometry.IDENTITY, new ListTag());
            if (best != null) {
                limit = farHalf.distance();
            }
        }

        final Optional<Pair<Portal, RayTraceResult>> through =
            PortalUtils.raytracePortals(level, eye, end, true, portal -> portal.isVisible() && IPPhysicsStaffPortals.isRigid(portal));
        if (through.isPresent() && eye.distanceTo(through.get().getSecond().hitPos()) < limit) {
            final Picked behind = pickBehind(player, level, through.get().getFirst(), through.get().getSecond().hitPos(), end, partialTick);
            if (behind != null) {
                best = behind;
            } else if (best == null) {
                // the portal hides whatever the staff's own pick found behind it
                return new PickResult(BlockHitResult.miss(end, Direction.getNearest(end.x - eye.x, end.y - eye.y, end.z - eye.z),
                    BlockPos.containing(end)), null);
            }
        }
        return best != null ? new PickResult(best.hit(), best) : new PickResult(own, null);
    }

    /**
     * @return the ship picked across a portal, which the staff takes in place of the one Simulated finds at the hit
     * location in the player's world
     */
    @Nullable
    public static ClientSubLevel pickedShip(@Nullable final ClientSubLevel own) {
        return picked != null ? picked.ship() : own;
    }

    public static void afterItemUsed() {
        lastPicked = picked;
        picked = null;
    }

    /**
     * @return the ship block seen through {@code portal} (hit at {@code portalHit}) along the ray to {@code end}, unless
     * terrain behind the portal is nearer
     */
    @Nullable
    private static Picked pickBehind(final LocalPlayer player, final ClientLevel level, final Portal portal, final Vec3 portalHit, final Vec3 end,
                                     final float partialTick) {
        final ClientLevel dest = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
        if (dest == null) {
            return null;
        }
        final Vec3 from = portal.transformPoint(portalHit);
        final Vec3 to = portal.transformPoint(end);
        final ClipContext terrainRay = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) terrainRay).sable$setSubLevelIgnoring(ship -> true);
        final BlockHitResult terrain = dest.clip(terrainRay);
        final double toTerrain = terrain.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : from.distanceTo(terrain.getLocation());
        final IPStraddlePick.Hit behind = IPStraddlePick.nearest(dest, from, to, partialTick, true);
        if (behind == null || behind.distance() >= toTerrain) {
            return null;
        }
        final ListTag chain = new ListTag();
        chain.add(link(portal.getUUID(), level.dimension(), false));
        return picked(behind, IPPortalIsometry.of(portal), chain);
    }

    /**
     * @param before the map from the player's world into the world {@code hit} was traced in
     * @param chain  the portals that lead there
     */
    @Nullable
    private static Picked picked(final IPStraddlePick.Hit hit, final IPPortalIsometry before, final ListTag chain) {
        final ClientLevel shipLevel = ClientWorldLoader.getOptionalWorld(hit.target().dimension());
        final SubLevel ship = shipLevel == null ? null : IPStraddleCut.shipAt(shipLevel, hit.target().hit().getBlockPos());
        if (!(ship instanceof final ClientSubLevel clientShip)) {
            return null;
        }
        IPPortalIsometry frame = before;
        if (hit.farHalf() != null) {
            frame = frame.then(hit.farHalf().isometry().inverse());
            chain.add(link(hit.farHalf().portal(), shipLevel.dimension(), true));
        }
        final CompoundTag tag = new CompoundTag();
        tag.put(IPPhysicsStaffPortals.LINKS, chain);
        return new Picked(clientShip, hit.target().hit(), frame, tag);
    }

    private static CompoundTag link(final UUID portal, final ResourceKey<Level> portalDim, final boolean inverse) {
        final CompoundTag link = new CompoundTag();
        link.putUUID(IPPhysicsStaffPortals.LINK_PORTAL, portal);
        link.putString(IPPhysicsStaffPortals.LINK_DIM, portalDim.location().toString());
        link.putBoolean(IPPhysicsStaffPortals.LINK_INVERSE, inverse);
        return link;
    }

    // ---- dragging

    /**
     * Simulated started dragging {@code ship}: a ship picked across a portal gets its distance and orientation as the
     * player sees it, and the server learns the portals.
     */
    public static void onDragStarted(@Nullable final PhysicsStaffClientHandler.ClientDragSession session, final SubLevel ship,
                                     final LocalPlayer player) {
        if (session == null) {
            drag = null;
            return;
        }
        final Picked across = picked != null && picked.ship() == ship ? picked : null;
        final IPPortalIsometry frame = across != null ? across.frame() : IPPortalIsometry.IDENTITY;
        drag = new Drag(session, ship.getUniqueId(), player.level().dimension(), frame);
        final CompoundTag chain = new CompoundTag();
        if (across != null) {
            final Vec3 anchor = ship.logicalPose().transformPosition(JOMLConversion.toMojang(session.dragLocalAnchor()));
            session.setDistance(Math.clamp(player.getEyePosition().distanceTo(frame.unmapPoint(anchor)), 2.0, PhysicsStaffItem.RANGE));
            session.dragOrientation().premul(new Quaterniond(frame.rotation()).conjugate());
            chain.put(IPPhysicsStaffPortals.LINKS, across.chain().getList(IPPhysicsStaffPortals.LINKS, CompoundTag.TAG_COMPOUND));
            LOGGER.info("[IPStaff] dragging ship {} of {} across a portal", ship.getUniqueId(), ship.getLevel().dimension().location());
        }
        McRemoteProcedureCallClient.tellServerToInvoke(SERVER + "grab", ship.getUniqueId(), ship.getLevel().dimension(), chain);
    }

    /**
     * Before Simulated's tick: forgets a finished drag, and continues a drag whose ship was moved to another dimension
     * on the moved ship once it is there.
     */
    public static void tick(final PhysicsStaffClientHandler handler) {
        final Drag current = drag;
        if (current == null) {
            return;
        }
        if (handler.getDragSession() != current.session) {
            drag = null;
            return;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.level().dimension().equals(current.playerDim)) {
            current.lost = true;
            return;
        }
        if (current.movedTo == null) {
            return;
        }
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(current.movedTo);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel moved = container == null ? null : container.getSubLevel(current.ship);
        if (moved == null || moved.isRemoved()) {
            if (++current.waited > MOVED_SHIP_TIMEOUT_TICKS) {
                LOGGER.info("[IPStaff] ship {} never showed up in {}; drag ended", current.ship, current.movedTo.location());
                current.lost = true;
            }
            return;
        }
        final PhysicsStaffClientHandler.ClientDragSession old = current.session;
        final Vector3d anchor = new Vector3d(old.dragLocalAnchor()).add(current.shift.getX(), current.shift.getY(), current.shift.getZ());
        final PhysicsStaffClientHandler.ClientDragSession next =
            new PhysicsStaffClientHandler.ClientDragSession(moved, anchor, old.dragOrientation(), old.distance());
        ((IPPhysicsStaffClientHandlerAccessor) handler).ip_setDragSession(next);
        // the beam's end is interpolated in plot coordinates: move all of it to the new plot at once
        final LocalPlayer self = Minecraft.getInstance().player;
        if (self != null && ((IPPhysicsStaffClientHandlerAccessor) handler).ip_getBeams().get(self.getUUID())
            instanceof final IPPhysicsStaffBeamAccessor beam) {
            final Vec3 shift = Vec3.atLowerCornerOf(current.shift);
            beam.ip_setEnd(beam.ip_getEnd().add(shift));
            beam.ip_setPreviousEnd(beam.ip_getPreviousEnd().add(shift));
            beam.ip_setServerEnd(beam.ip_getServerEnd().add(shift));
        }
        current.session = next;
        if (current.pendingMove != null) {
            current.frame = current.frame.then(current.pendingMove);
            current.pendingMove = null;
        }
        current.movedTo = null;
        current.shift = BlockPos.ZERO;
        current.removedFor = 0;
    }

    /**
     * Simulated stops a drag whose ship is removed; a dragged ship moved to another dimension is removed here first and
     * shows up there later.
     */
    public static boolean isDragLost(final SubLevel ship, final boolean removed) {
        final Drag current = drag;
        if (current == null || !ship.getUniqueId().equals(current.ship)) {
            return removed;
        }
        if (current.lost) {
            return true;
        }
        if (!removed) {
            current.removedFor = 0;
            return false;
        }
        return current.movedTo == null && ++current.removedFor > MOVE_MESSAGE_GRACE_TICKS;
    }

    /**
     * The local player went through {@code portal}: the drag's orientation turns with the player's view, and its frame
     * now starts in the destination.
     */
    public static void onPlayerTeleported(final Portal portal) {
        final Drag current = drag;
        if (current == null) {
            return;
        }
        if (!IPPhysicsStaffPortals.isRigid(portal)) {
            current.lost = true;
            return;
        }
        final IPPortalIsometry through = IPPortalIsometry.of(portal);
        current.frame = through.inverse().then(current.frame);
        current.playerDim = portal.getDestDim();
        current.session.dragOrientation().premul(through.rotation());
    }

    // ---- drawing

    /**
     * @return for the local player's beam, the ship its end is on (Simulated looks it up in the player's world)
     */
    @Nullable
    public static ClientSubLevel beamShip(final UUID owner, @Nullable final ClientSubLevel own) {
        if (!isLocalPlayer(owner)) {
            return own;
        }
        final Drag current = drag;
        if (current != null) {
            // while a moved ship is awaited the old one stays, at its last pose
            return current.session.dragSubLevel() instanceof final ClientSubLevel ship && (!ship.isRemoved() || !current.lost) ? ship : null;
        }
        if (lastPicked != null) {
            return lastPicked.ship().isRemoved() ? null : lastPicked.ship();
        }
        return own;
    }

    /**
     * @return for a beam in a portal pass: the local player when its beam goes across a portal (Simulated draws only the
     * beams of players in the rendered world, so the part of the beam behind the portal was never drawn)
     */
    @Nullable
    public static Player beamPlayer(final UUID owner, @Nullable final Player own) {
        if (own != null || !PortalRendering.isRendering() || !isLocalPlayer(owner) || beamFrame() == null) {
            return own;
        }
        return Minecraft.getInstance().player;
    }

    /**
     * @param end the beam end in the ship's world
     * @return the local player's beam {start, end} in the rendered world, or null when this pass does not draw it: the
     * player's world draws the beam as seen (the ship's position mapped by the drag's frame), the view through the
     * portal the beam goes through draws it mapped through that portal (IP's clipping keeps the part behind the portal)
     */
    @Nullable
    public static Vec3[] beamSegment(final UUID owner, final Vec3 start, final Vec3 end) {
        if (!isLocalPlayer(owner)) {
            return new Vec3[]{start, end};
        }
        final IPPortalIsometry frame = beamFrame();
        final Vec3 seen = frame == null ? end : frame.unmapPoint(end);
        if (!PortalRendering.isRendering()) {
            beamStart = start;
            return new Vec3[]{start, seen};
        }
        if (frame == null || PortalRendering.getPortalLayer() != 1) {
            return null;
        }
        // the focus point follows the main camera's rotation: take it from the player's own pass
        final Vec3 from = beamStart != null ? beamStart : start;
        final Portal portal = PortalRendering.getRenderingPortal();
        if (portal.rayTrace(from, seen) == null) {
            return null;
        }
        return new Vec3[]{portal.transformPoint(from), portal.transformPoint(seen)};
    }

    @Nullable
    private static IPPortalIsometry beamFrame() {
        return drag != null ? drag.frame : lastPicked != null ? lastPicked.frame() : null;
    }

    /**
     * @param point a point of the dragged ship, in the ship's world
     * @return it in the player's world (where the staff in the player's hand points)
     */
    public static Vector3d aimPoint(final Vector3d point) {
        final Drag current = drag;
        if (current == null) {
            return point;
        }
        final Vec3 seen = current.frame.unmapPoint(new Vec3(point.x, point.y, point.z));
        return point.set(seen.x, seen.y, seen.z);
    }

    /**
     * @return whether the lock icon of {@code ship} (of the world being rendered) is hidden: a portal is in front of it
     * (the icon is drawn without depth test, so IP's portal would not cover it)
     */
    public static boolean hidesLockIcon(final SubLevel ship) {
        if (!(ship instanceof final ClientSubLevel clientShip)) {
            return false;
        }
        final Vector3dc position = clientShip.renderPose().position();
        final Vec3 pos = new Vec3(position.x(), position.y(), position.z());
        if (PortalRendering.isRendering()) {
            final Plane plane = PortalRendering.getActiveClippingPlane();
            return plane != null && !plane.isPointOnPositiveSide(pos);
        }
        final Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return PortalUtils.raytracePortals(ship.getLevel(), camera, pos, true, Portal::isVisible).isPresent();
    }

    /**
     * @return {@code point} of {@code ship}'s world in the player's world
     */
    public static Vec3 toPlayerWorld(final SubLevel ship, final Vec3 point) {
        final IPPortalIsometry frame = frameOf(ship);
        return frame == null ? point : frame.unmapPoint(point);
    }

    /**
     * @return where a particle at {@code plotPos} of {@code ship} goes in the player's world {@code level}: unchanged
     * for a ship of that world (Sable draws plot-space particles on the ship)
     */
    public static Vec3 particlePos(final SubLevel ship, final Level level, final Vec3 plotPos) {
        if (ship.getLevel() == level || frameOf(ship) == null) {
            return plotPos;
        }
        return toPlayerWorld(ship, ship.logicalPose().transformPosition(plotPos));
    }

    /**
     * @return whether Simulated's hover box (the dragged block, in plot coordinates of the player's world) must not be
     * drawn: the dragged ship is of another world
     */
    public static boolean hidesHoverBox(@Nullable final Level level) {
        final Drag current = drag;
        return current != null && (level == null || !isShown(current.session.dragSubLevel(), level));
    }

    /**
     * @return whether {@code ship} is drawn in {@code level}: its own world, or the destination of a portal it straddles
     * (its far half)
     */
    private static boolean isShown(final SubLevel ship, final Level level) {
        return ship.getLevel() == level || IPStraddleCut.projectionOf(level, ship) != null;
    }

    /**
     * Draws Simulated's selection box (the hovered or dragged block, {@code physicsStaffSelection} in Create's outliner)
     * in a pass rendering {@code Minecraft.level}. Sable puts plot-space outlines on the ship of the rendered world
     * found at that plot position; for the far half of a ship straddling into this world that is no ship, or another
     * one: here the ship's own world is made current for the draw (Sable finds the ship) and the result mapped through
     * the portal. Elsewhere the box is not drawn.
     */
    public static void renderSelection(final Vec3 camera, final PoseStack poseStack, final Runnable render) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel rendered = minecraft.level;
        final SubLevel ship = selectionShip();
        if (ship == null || rendered == null || ship.getLevel() == rendered) {
            render.run();
            return;
        }
        final IPStraddleCut.Projection projection = IPStraddleCut.projectionOf(rendered, ship);
        if (projection == null || !(ship.getLevel() instanceof final ClientLevel shipLevel)) {
            return;
        }
        // Sable leaves the outline at (ship-world position - camera); this maps it to (mapped position - camera)
        final IPPortalIsometry isometry = projection.isometry();
        final Vec3 offset = isometry.mapPoint(camera).subtract(camera);
        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        poseStack.mulPose(new Quaternionf().set(isometry.rotation()));
        minecraft.level = shipLevel;
        try {
            render.run();
        } finally {
            minecraft.level = rendered;
            poseStack.popPose();
        }
    }

    /**
     * @return the ship of the staff's selection box: the dragged one, or the one hovered across a portal (null: the
     * staff's own pick, in the player's world)
     */
    @Nullable
    private static SubLevel selectionShip() {
        final Drag current = drag;
        if (current != null) {
            return current.session.dragSubLevel();
        }
        final PickResult result = hover;
        return result != null && result.picked() != null ? result.picked().ship() : null;
    }

    @Nullable
    private static IPPortalIsometry frameOf(final SubLevel ship) {
        if (picked != null && picked.ship() == ship) {
            return picked.frame();
        }
        if (drag != null && drag.ship.equals(ship.getUniqueId())) {
            return drag.frame;
        }
        if (lastPicked != null && lastPicked.ship() == ship) {
            return lastPicked.frame();
        }
        return null;
    }

    private static boolean isLocalPlayer(final UUID id) {
        final LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getUUID().equals(id);
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /**
         * The server moved the dragged ship {@code ship} through a portal ({@code origin}, {@code dest}, {@code rotation})
         * to {@code dim}, its plot by {@code shift}; the drag goes on there.
         */
        public static void onShipMoved(final UUID ship, final ResourceKey<Level> dim, final BlockPos shift, final Vec3 origin,
                                       final Vec3 dest, final DQuaternion rotation) {
            final Drag current = drag;
            if (current == null || !current.ship.equals(ship)) {
                return;
            }
            final IPPortalIsometry through = new IPPortalIsometry(origin, dest,
                new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w).normalize());
            // the frame changes with the ship the drag is on (until then: the old one, at its last pose)
            current.pendingMove = current.pendingMove == null ? through : current.pendingMove.then(through);
            current.shift = current.movedTo != null ? current.shift.offset(shift) : shift;
            current.movedTo = dim;
            current.waited = 0;
            LOGGER.info("[IPStaff] dragged ship {} moved to {}", ship, dim.location());
        }
    }
}
