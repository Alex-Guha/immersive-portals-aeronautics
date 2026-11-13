/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.index.SimTags;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalSimilarity;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Create Aeronautics (Simulated) handles on ships across portals, client side (docs/lessons/handles.md).
 * <ul>
 *     <li>A handle on a far half or seen through a portal is taken hold of in the ship's own world: from IP's remote
 *     target (Simulated's {@code useItemOn} runs there) or from Simulated's catch while holding use, which also looks
 *     across portals ({@link #sweepClip}). The portals come from the physics staff's pick
 *     ({@link IPPhysicsStaffPortalsClient#pickAlong}); the server is told them ({@code IPHandlePortals.RemoteCallables.hold}).</li>
 *     <li>While holding, Simulated's tick runs in the ship's world ({@link #holdLevel}) and pulls the player to the
 *     handle where it is seen: {@link Hold#frame} maps the player's world into the ship's. It changes when the player
 *     goes through a portal ({@link #onPlayerTeleported}) or the server moves the ship to another dimension
 *     ({@link RemoteCallables#onShipMoved}), whereupon the hold goes on with the moved ship.</li>
 * </ul>
 */
public final class IPHandlePortalsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVER = "qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortals.RemoteCallables.";
    /** Sable's removal of a moved ship reaches the client before the server's message that the hold follows it. */
    private static final int MOVE_MESSAGE_GRACE_TICKS = 10;
    /** How long a hold waits for its moved ship to appear in the destination world. */
    private static final int MOVED_SHIP_TIMEOUT_TICKS = 100;

    /** The local player's hold of a handle on a ship. */
    private static final class Hold {
        final UUID ship;
        ResourceKey<Level> shipDim;
        ResourceKey<Level> playerDim;
        /** Maps the player's world ({@link #playerDim}) into the ship's world. */
        IPPortalSimilarity frame;
        /** The portals the handle was seen through, for the server. */
        final ListTag chain;
        /** The dimension the ship was moved to, until it shows up there. */
        @Nullable
        ResourceKey<Level> movedTo;
        /** The portal map of that move, applied to {@link #frame} when the hold goes on with the moved ship. */
        @Nullable
        IPPortalSimilarity pendingMove;
        BlockPos shift = BlockPos.ZERO;
        int waited;
        int removedFor;
        /** The hold cannot go on: it ends at the next tick. */
        boolean lost;

        Hold(final UUID ship, final ResourceKey<Level> shipDim, final ResourceKey<Level> playerDim, final IPPortalSimilarity frame,
             final ListTag chain) {
            this.ship = ship;
            this.shipDim = shipDim;
            this.playerDim = playerDim;
            this.frame = frame;
            this.chain = chain;
        }
    }

    /**
     * What a tick of the hold does, before Simulated's.
     */
    public enum TickAction {
        /** Simulated's tick runs. */
        RUN,
        /** The hold waits for its ship (moved to another dimension): it is kept and Simulated's tick skipped. */
        WAIT,
        /** The hold ends. */
        STOP
    }

    @Nullable
    private static Hold hold;
    /** The hold being taken, from the head of Simulated's {@code startHold} until it replaced the previous one. */
    @Nullable
    private static Hold starting;
    /** A handle across a portal found by Simulated's catch this tick. */
    @Nullable
    private static IPPhysicsStaffPortalsClient.Picked swept;

    private IPHandlePortalsClient() {
    }

    // ---- taking hold

    /**
     * Head of Simulated's {@code startHold}: works out how the player sees the handle at {@code pos} of {@code level}
     * (another world than the player's for IP's remote target and a catch across a portal).
     */
    public static void beforeStartHold(final Level level, final Player player, final BlockPos pos) {
        starting = null;
        if (!(player instanceof final LocalPlayer local) || !(level instanceof final ClientLevel clientLevel)) {
            return;
        }
        final SubLevel ship = IPStraddleCut.shipAt(clientLevel, pos);
        if (ship == null) {
            return;
        }
        final IPPhysicsStaffPortalsClient.Picked across;
        if (swept != null && swept.ship() == ship && swept.hit().getBlockPos().equals(pos)) {
            across = swept;
        } else if (clientLevel != local.level() || isRemoteTarget(clientLevel, pos)) {
            across = pickAcross(local, ship);
            if (across == null) {
                LOGGER.info("[IPHandle] the handle at {} on ship {} of {} is not seen across a portal; not held", pos, ship.getUniqueId(),
                    clientLevel.dimension().location());
                return;
            }
        } else {
            across = null;
        }
        starting = across == null
            ? new Hold(ship.getUniqueId(), clientLevel.dimension(), local.level().dimension(), IPPortalSimilarity.IDENTITY, new ListTag())
            : new Hold(ship.getUniqueId(), clientLevel.dimension(), local.level().dimension(), across.frame(),
            across.chain().getList(IPPhysicsStaffPortals.LINKS, CompoundTag.TAG_COMPOUND));
    }

    /**
     * Simulated's {@code startHold} replaced any previous hold with this one (and let go of the previous handle): the
     * server learns which ship and how it is seen.
     */
    public static void afterHoldReplaced(final BlockPos pos) {
        hold = starting;
        starting = null;
        swept = null;
        if (hold == null) {
            return;
        }
        final CompoundTag chain = new CompoundTag();
        chain.put(IPPhysicsStaffPortals.LINKS, hold.chain);
        McRemoteProcedureCallClient.tellServerToInvoke(SERVER + "hold", hold.ship, hold.shipDim, pos, chain);
        if (!hold.chain.isEmpty()) {
            LOGGER.info("[IPHandle] holding a handle on ship {} of {} across a portal", hold.ship, hold.shipDim.location());
        }
    }

    public static void onStopped() {
        hold = null;
    }

    /**
     * @return whether the block at {@code pos} of {@code level} is IP's remote target (through a portal or on a far
     * half): Simulated's {@code useItemOn} runs for it
     */
    private static boolean isRemoteTarget(final ClientLevel level, final BlockPos pos) {
        return level.dimension().equals(BlockManipulationClient.remotePointedDim)
            && BlockManipulationClient.remoteHitResult instanceof final BlockHitResult hit && hit.getBlockPos().equals(pos);
    }

    /**
     * @return the handle's ship seen across a portal along the player's view, with the portals
     */
    @Nullable
    private static IPPhysicsStaffPortalsClient.Picked pickAcross(final LocalPlayer player, final SubLevel ship) {
        final Vec3 eye = player.getEyePosition();
        final Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
        final IPPhysicsStaffPortalsClient.Picked picked =
            IPPhysicsStaffPortalsClient.pickAlong(player, eye, end, BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end)), 1.0f).picked();
        return picked != null && picked.ship() == ship ? picked : null;
    }

    // ---- catching a handle while holding use

    /**
     * In place of the raycast of Simulated's catch (in the player's world): a handle on a far half or behind a portal
     * is caught when nearer than what the raycast hits.
     */
    public static BlockHitResult sweepClip(final Level level, final ClipContext context, final Supplier<BlockHitResult> own) {
        final BlockHitResult hit = own.get();
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.level() != level) {
            return hit;
        }
        final IPPhysicsStaffPortalsClient.PickResult result = IPPhysicsStaffPortalsClient.pickAlong(player, context.getFrom(), context.getTo(), hit, 1.0f);
        final IPPhysicsStaffPortalsClient.Picked picked = result.picked();
        if (picked == null) {
            return result.hit() instanceof final BlockHitResult block ? block : hit;
        }
        if (!picked.ship().getLevel().getBlockState(picked.hit().getBlockPos()).is(SimTags.Blocks.HANDLES)) {
            return hit;
        }
        swept = picked;
        return picked.hit();
    }

    /**
     * @return for Simulated's catch: the block at {@code pos}, read in the world of a handle caught across a portal
     */
    public static BlockState sweptState(final Level level, final BlockPos pos, final Supplier<BlockState> own) {
        final IPPhysicsStaffPortalsClient.Picked picked = swept;
        return picked != null && picked.hit().getBlockPos().equals(pos) ? picked.ship().getLevel().getBlockState(pos) : own.get();
    }

    /**
     * @return for Simulated's catch: the world to take hold in, that of a handle caught across a portal
     */
    public static Level sweptLevel(final Level level, final BlockPos pos) {
        final IPPhysicsStaffPortalsClient.Picked picked = swept;
        return picked != null && picked.hit().getBlockPos().equals(pos) ? picked.ship().getLevel() : level;
    }

    public static void beforeSweep() {
        swept = null;
    }

    // ---- holding

    /**
     * Before Simulated's tick of the hold: continues a hold whose ship was moved to another dimension on the moved ship
     * once it is there.
     *
     * @param pos    the held handle (Simulated's interaction position)
     * @param setPos moves it to the moved ship's plot
     */
    public static TickAction beforeTick(@Nullable final BlockPos pos, final Consumer<BlockPos> setPos) {
        final Hold current = hold;
        if (current == null || pos == null) {
            return TickAction.RUN;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        if (current.lost || player == null || !player.level().dimension().equals(current.playerDim)) {
            LOGGER.info("[IPHandle] let go: lost={} player in {} (hold's world {})", current.lost,
                player == null ? null : player.level().dimension().location(), current.playerDim.location());
            return TickAction.STOP;
        }
        if (current.movedTo != null) {
            // the copy holding the handle's new plot position: within one world the old copy has the same UUID and may
            // still be here (the ship moves to another plot, IPSableBridge.reloadInDestination)
            final ClientLevel movedLevel = ClientWorldLoader.getOptionalWorld(current.movedTo);
            final SubLevel atHandle = movedLevel == null ? null
                : dev.ryanhcode.sable.Sable.HELPER.getContaining(movedLevel, pos.offset(current.shift));
            final SubLevel moved = atHandle != null && !atHandle.isRemoved() && atHandle.getUniqueId().equals(current.ship) ? atHandle : null;
            if (moved == null) {
                if (++current.waited > MOVED_SHIP_TIMEOUT_TICKS) {
                    LOGGER.info("[IPHandle] ship {} never showed up in {}; let go", current.ship, current.movedTo.location());
                    return TickAction.STOP;
                }
                return TickAction.WAIT;
            }
            setPos.accept(pos.offset(current.shift));
            current.shipDim = current.movedTo;
            if (current.pendingMove != null) {
                current.frame = current.frame.then(current.pendingMove);
                current.pendingMove = null;
            }
            current.movedTo = null;
            current.shift = BlockPos.ZERO;
            current.removedFor = 0;
            return TickAction.RUN;
        }
        if (shipIn(current.shipDim, current.ship) == null) {
            if (++current.removedFor > MOVE_MESSAGE_GRACE_TICKS) {
                LOGGER.info("[IPHandle] let go: ship {} gone from {}", current.ship, current.shipDim.location());
                return TickAction.STOP;
            }
            return TickAction.WAIT;
        }
        current.removedFor = 0;
        return TickAction.RUN;
    }

    /**
     * @param own the world Simulated ticks the hold in (the player's)
     * @return the held ship's world
     */
    public static Level holdLevel(final Level own) {
        final Hold current = hold;
        if (current == null || current.shipDim.equals(own.dimension())) {
            return own;
        }
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(current.shipDim);
        return level != null ? level : own;
    }

    /**
     * @param center the held handle's grab centre in the world of its ship
     * @return where the player sees it, in place
     */
    public static Vector3d grabCenterSeen(final Vector3d center) {
        return seen(hold, center);
    }

    /**
     * As {@link #grabCenterSeen}, for the handle being taken hold of (Simulated's {@code startHold}).
     */
    public static Vector3d startingGrabCenterSeen(final Vector3d center) {
        return seen(starting, center);
    }

    private static Vector3d seen(@Nullable final Hold of, final Vector3d center) {
        if (of != null) {
            final Vec3 mapped = of.frame.unmapPoint(new Vec3(center.x, center.y, center.z));
            center.set(mapped.x, mapped.y, mapped.z);
        }
        return center;
    }

    /**
     * The local player went through {@code portal}: the hold's frame now starts in the destination.
     */
    public static void onPlayerTeleported(final Portal portal) {
        final Hold current = hold;
        if (current == null) {
            return;
        }
        if (!IPPhysicsStaffPortals.isMappable(portal)) {
            current.lost = true;
            return;
        }
        onPlayerTeleported(IPPhysicsStaffPortals.mapOf(portal), portal.getDestDim());
    }

    /**
     * As {@link #onPlayerTeleported(Portal)}, for a portal's map {@code through} into {@code dim}.
     */
    static void onPlayerTeleported(final IPPortalSimilarity through, final ResourceKey<Level> dim) {
        final Hold current = hold;
        if (current == null) {
            return;
        }
        current.frame = through.inverse().then(current.frame);
        current.playerDim = dim;
    }

    @Nullable
    private static SubLevel shipIn(final ResourceKey<Level> dim, final UUID ship) {
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(dim);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel found = container == null ? null : container.getSubLevel(ship);
        return found == null || found.isRemoved() ? null : found;
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /**
         * The server moved the held ship {@code ship} through a portal ({@code origin}, {@code dest}, {@code rotation})
         * to {@code dim}, its plot by {@code shift}; the hold goes on there.
         */
        public static void onShipMoved(final UUID ship, final ResourceKey<Level> dim, final BlockPos shift, final Vec3 origin,
                                       final Vec3 dest, final DQuaternion rotation, final double scale) {
            final Hold current = hold;
            if (current == null || !current.ship.equals(ship)) {
                return;
            }
            final IPPortalSimilarity through = new IPPortalSimilarity(new IPPortalIsometry(origin, dest,
                new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w).normalize()), scale);
            current.pendingMove = current.pendingMove == null ? through : current.pendingMove.then(through);
            current.shift = current.movedTo != null ? current.shift.offset(shift) : shift;
            current.movedTo = dim;
            current.waited = 0;
            LOGGER.info("[IPHandle] held ship {} moved to {}", ship, dim.location());
        }
    }
}
