/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import qouteall.imm_ptl.core.compat.sable_straddle.IPShipScale;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.animation.AnimationContext;
import qouteall.imm_ptl.core.portal.animation.AnimationResult;
import qouteall.imm_ptl.core.portal.animation.DeltaUnilateralPortalState;
import qouteall.imm_ptl.core.portal.animation.PortalAnimationDriver;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.UUID;

/**
 * The animation driver of a ship-mounted portal (docs/lessons/ship-mounted-portals.md): on the ship-side
 * portal that holds the cluster's animation, it places the portal where its frame is on the carrier ship. On the other
 * side of the holder's animation it does the same for the far end when that end is on a ship too
 * (docs/lessons/ship-mounted-portals.md, "Both ends on ships"); {@link #dimension} is then the far end's.
 * <p>
 * The reference state of the holder's side it drives is that side in the carrier's plot ({@link #plotOrigin} is its
 * position), and the delta is the carrier's pose: offset {@code pose(p) - p}, rotation the pose's orientation. So the
 * portal follows the plot like the ship's blocks do, independent of the centre of mass (the pose's position moves when
 * blocks change). IP's animation runs this on the server every tick and on the client every tick and before rendering,
 * applies it with {@code setPortalState}, moves the rest of the cluster ({@code rectifyClusterPortals}) and keeps the
 * last-tick and this-tick states its teleports through moving portals use.
 * <p>
 * On the server the pose is this tick's (Sable's physics runs at the head of the level tick, before entities). On the
 * client a frame uses the render pose Sable draws the hull with, a tick uses the logical pose. Without the carrier (not
 * loaded yet, not synced to this client) the last delta is kept, which is also saved, so a reloaded portal stays where
 * it was until its ship is back.
 */
public final class IPShipPortalDriver implements PortalAnimationDriver, IPShipMount {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("ip_aeronautics", "ship_anchor");

    public static void init() {
        PortalAnimationDriver.registerDeserializer(ID, IPShipPortalDriver::fromTag);
    }

    public final ResourceKey<Level> dimension;
    public final UUID carrier;
    /** The origin of the end it drives in the carrier's plot (the holder's reference position on that side). */
    public final Vec3 plotOrigin;
    private Vec3 lastOffset;
    private DQuaternion lastRotation;
    /** The carrier's scale at the last result (Create: Pocket Sized), 1 for an unscaled carrier. */
    private double lastScale;
    /** Whether the last result came from the carrier (false: it was missing and the last delta was kept). */
    private boolean carrierSeen;

    /**
     * Create Teleporters 3.0.2+ calls this constructor by reflection (AeronauticsPortalIntegration): keep it. The last
     * delta of an unscaled carrier.
     */
    public IPShipPortalDriver(final ResourceKey<Level> dimension, final UUID carrier, final Vec3 plotOrigin,
                              final Vec3 lastOffset, final DQuaternion lastRotation) {
        this(dimension, carrier, plotOrigin, lastOffset, lastRotation, 1.0);
    }

    public IPShipPortalDriver(final ResourceKey<Level> dimension, final UUID carrier, final Vec3 plotOrigin,
                              final Vec3 lastOffset, final DQuaternion lastRotation, final double lastScale) {
        this.dimension = dimension;
        this.carrier = carrier;
        this.plotOrigin = plotOrigin;
        this.lastOffset = lastOffset;
        this.lastRotation = lastRotation;
        this.lastScale = lastScale;
    }

    private static IPShipPortalDriver fromTag(final CompoundTag tag) {
        return new IPShipPortalDriver(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dimension"))),
                tag.getUUID("carrier"),
                Helper.getVec3d(tag, "plotOrigin"),
                Helper.getVec3d(tag, "lastOffset"),
                DQuaternion.fromTag(tag.getCompound("lastRotation")),
                tag.contains("lastScale") ? tag.getDouble("lastScale") : 1.0);
    }

    @Override
    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("type", ID.toString());
        tag.putString("dimension", this.dimension.location().toString());
        tag.putUUID("carrier", this.carrier);
        Helper.putVec3d(tag, "plotOrigin", this.plotOrigin);
        Helper.putVec3d(tag, "lastOffset", this.lastOffset);
        tag.put("lastRotation", this.lastRotation.toTag());
        if (!isUnscaled(this.lastScale)) {
            tag.putDouble("lastScale", this.lastScale);
        }
        return tag;
    }

    /**
     * The delta that puts a plot-space state where the carrier's {@code pose} puts its blocks: its centre through the
     * pose, its orientation turned with the ship, and its size times the ship's scale (Create: Pocket Sized), so a
     * portal on a scaled ship fills its frame. IP then treats it as a scaled portal (the two ends differ in size).
     */
    public static DeltaUnilateralPortalState deltaFor(final Pose3dc pose, final Vec3 plotOrigin) {
        final Vector3d world = pose.transformPosition(new Vector3d(plotOrigin.x, plotOrigin.y, plotOrigin.z));
        final Quaterniondc q = pose.orientation();
        return new DeltaUnilateralPortalState(
                new Vec3(world.x - plotOrigin.x, world.y - plotOrigin.y, world.z - plotOrigin.z),
                new DQuaternion(q.x(), q.y(), q.z(), q.w()),
                sizeScaling(IPShipScale.of(pose)));
    }

    @Nullable
    private static Vec3 sizeScaling(final double scale) {
        return isUnscaled(scale) ? null : new Vec3(scale, scale, scale);
    }

    private static boolean isUnscaled(final double scale) {
        return Math.abs(scale - 1.0) <= 1.0E-6;
    }

    /** The carrier's scale when this end was last placed (1 unscaled). */
    public double scale() {
        return this.lastScale;
    }

    @Override
    public @NotNull AnimationResult getAnimationResult(final long tickTime, final float partialTicks, final AnimationContext context) {
        final Pose3dc pose = carrierPose(context);
        if (pose != null) {
            final DeltaUnilateralPortalState delta = deltaFor(pose, this.plotOrigin);
            this.lastOffset = delta.offset();
            this.lastRotation = delta.rotation();
            this.lastScale = delta.sizeScaling() == null ? 1.0 : delta.sizeScaling().x;
            this.carrierSeen = true;
            return new AnimationResult(delta, false);
        }
        this.carrierSeen = false;
        return new AnimationResult(new DeltaUnilateralPortalState(this.lastOffset, this.lastRotation,
                sizeScaling(this.lastScale)), false);
    }

    @Nullable
    private Pose3dc carrierPose(final AnimationContext context) {
        if (context.isClientSide()) {
            return IPShipPortalsClientAccess.carrierPose(this.dimension, this.carrier, context.isTicking());
        }
        final Level level = MiscHelper.getServer().getLevel(this.dimension);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(this.carrier);
        return ship == null || ship.isRemoved() ? null : ship.logicalPose();
    }

    @Override
    public Vec3 plotOrigin() {
        return this.plotOrigin;
    }

    @Override
    public UUID carrier() {
        return this.carrier;
    }

    /** Whether the last result came from the carrier itself (server: the carrier is loaded). */
    public boolean carrierSeen() {
        return this.carrierSeen;
    }

    /**
     * Null: when the driver is removed, the portal stays where it is (IP applies an ending delta on top of the current
     * state, which already contains this driver's delta).
     */
    @Override
    public @Nullable DeltaUnilateralPortalState getEndingResult(final long tickTime, final AnimationContext context) {
        return null;
    }

    @Override
    public Component getInfo() {
        return Component.literal("Mounted on ship " + this.carrier);
    }

    /** Client side: the carrier's pose in a client level ({@link IPShipPortalDriver}'s frame and tick rules). */
    static Pose3dc clientPose(final SubLevel ship, final boolean ticking) {
        if (ship instanceof final ClientSubLevel clientShip && !ticking) {
            return clientShip.renderPose();
        }
        return ship.logicalPose();
    }
}
