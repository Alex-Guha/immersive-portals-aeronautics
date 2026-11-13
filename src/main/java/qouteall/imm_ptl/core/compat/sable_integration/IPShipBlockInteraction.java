/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleInteraction;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddlePick;

import java.util.List;

/**
 * Block interaction with Sable ships across portals, for IP's block manipulation (docs/lessons/straddle-interaction.md).
 * Safe to call without Sable: every method then answers as if there were no ships.
 * <ul>
 *     <li>client: a ship hit is in plot coordinates; {@link #toWorld} gives where it is. Ships seen through a portal
 *     and far halves of straddling ships are targeted as IP remote targets in the ship's own dimension
 *     ({@link #refineRemoteTarget}, {@link #pickFarHalf});</li>
 *     <li>server: the reach check measures a ship block where the ship and its far halves are ({@link #reachPoints});
 *     block changes on a ship reach the players who see it through a portal ({@link #withPortalViewers}).</li>
 * </ul>
 */
public final class IPShipBlockInteraction {
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    /**
     * A block targeted on a ship.
     *
     * @param dimension the ship's own dimension
     * @param hit       the hit, in that dimension's plot coordinates
     */
    public record Target(ResourceKey<Level> dimension, BlockHitResult hit) {
    }

    private IPShipBlockInteraction() {
    }

    /**
     * @return the world position of a hit location in {@code level} (a ship hit is in plot coordinates)
     */
    public static Vec3 toWorld(final Level level, final Vec3 location) {
        if (!SABLE || !IPSableCompatHelper.isPlotSpacePosition(location.x, location.z)) {
            return location;
        }
        return IPStraddleInteraction.toWorld(level, location);
    }

    /**
     * @return whether the block at {@code pos} of {@code level}, a portal block, is on a ship with a portal at it: IP
     * gives a portal block no outline where a portal is ({@code MixinClipContext}), looking at the block's own
     * position, which on a ship is in plot space
     */
    public static boolean isShipPortalBlock(final Level level, final BlockPos pos) {
        return SABLE && IPSableCompatHelper.isPlotSpacePosition(pos.getX() + 0.5, pos.getZ() + 0.5)
            && IPStraddleInteraction.isShipPortalBlock(level, pos);
    }

    /**
     * Client, before IP looks for a target through a portal: forgets the previous far-half target.
     */
    public static void onPickStart() {
        if (SABLE) {
            IPStraddlePick.onPickStart();
        }
    }

    /**
     * Client: IP traced a ray through a portal into {@code world} and found {@code terrain}; ships of that world, and
     * far halves of ships straddling into it, may be nearer.
     *
     * @param from the ray start in {@code world} (on the portal)
     * @param to   the ray end in {@code world}
     * @return the nearer ship target, or null when the terrain hit stands
     */
    @Nullable
    public static Target refineRemoteTarget(final Level world, final Vec3 from, final Vec3 to, final BlockHitResult terrain,
                                            final float partialTick) {
        if (!SABLE) {
            return null;
        }
        return IPStraddlePick.refineRemoteTarget(world, from, to, terrain, partialTick);
    }

    /**
     * Client, after IP picked its target: the far half of a ship straddling into the player's own world may be nearer.
     *
     * @param limit how far the current target is along the ray (to the portal when IP points through one)
     * @return the far-half target, or null
     */
    @Nullable
    public static Target pickFarHalf(final Level level, final Vec3 from, final Vec3 to, final double limit,
                                     final float partialTick) {
        if (!SABLE) {
            return null;
        }
        return IPStraddlePick.pickFarHalf(level, from, to, limit, partialTick);
    }

    /**
     * Client: the player is seated on a straddling ship with the head past the portal plane: the portal the view and the
     * crosshair go through, or null ({@code IPStraddleSeatView}).
     */
    @Nullable
    public static qouteall.imm_ptl.core.portal.Portal seatedViewPortal(final float partialTick) {
        if (!SABLE) {
            return null;
        }
        final qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatView.Crossing crossing =
            qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatView.crossing(partialTick);
        return crossing == null ? null : crossing.portal();
    }

    /**
     * Client: as {@link #seatedViewPortal}, the point in front of the plane IP's cross-portal view looks from, or null.
     */
    @Nullable
    public static Vec3 seatedViewOrigin(final float partialTick) {
        if (!SABLE) {
            return null;
        }
        final qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatView.Crossing crossing =
            qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatView.crossing(partialTick);
        return crossing == null ? null : crossing.origin();
    }

    /**
     * @return whether the current IP remote target is on the far half of a straddling ship: it is drawn in the
     * destination dimension, not in the ship's own
     */
    public static boolean isFarHalfTarget() {
        return SABLE && IPStraddlePick.isFarHalfTarget();
    }

    /**
     * Server: where the block at {@code pos} of {@code world} is, as (dimension, position) pairs.
     */
    public static List<Tuple<ResourceKey<Level>, Vec3>> reachPoints(final ServerLevel world, final BlockPos pos) {
        if (!SABLE) {
            return List.of(new Tuple<>(world.dimension(), Vec3.atCenterOf(pos)));
        }
        return IPStraddleInteraction.reachPoints(world, pos);
    }

    /**
     * Server: the recipients of a change to a ship's plot chunk, plus the players the ship is synced to through a
     * portal or near its far half (Sable sends plot chunk changes only to players tracking the ship in its own world).
     */
    public static List<ServerPlayer> withPortalViewers(final ServerLevel level, final ChunkPos pos, final List<ServerPlayer> players) {
        if (!SABLE) {
            return players;
        }
        return IPShipThroughPortalSync.withPortalViewers(level, pos, players);
    }
}
