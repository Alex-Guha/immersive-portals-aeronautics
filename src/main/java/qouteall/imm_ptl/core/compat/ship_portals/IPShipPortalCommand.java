/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.imm_ptl.peripheral.wand.ProtoPortal;
import qouteall.imm_ptl.peripheral.wand.ProtoPortalSide;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /ip_aeronautics shipportals on|off|limited [range]|status}, {@code mount|unmount [portals]} (custom portals: the one the player
 * looks at, or the given portal entities) and the test hooks
 * {@code /ip_aeronautics shipportals debug light <pos>}, {@code debug wand <a> <b> <c> <dimension> <d> <e> <f>} and
 * {@code debug disassemble <pos> <goal> <quarterTurns>}
 * (operators only). Registered only with Sable present.
 */
public final class IPShipPortalCommand {
    private IPShipPortalCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ip_aeronautics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("shipportals")
                        .then(Commands.literal("on").executes(context -> setMode(context, IPGlobal.ShipPortalMode.unlimited)))
                        .then(Commands.literal("off").executes(context -> setMode(context, IPGlobal.ShipPortalMode.off)))
                        .then(Commands.literal("limited")
                                .executes(context -> setMode(context, IPGlobal.ShipPortalMode.limited))
                                .then(Commands.argument("range", IntegerArgumentType.integer(IPConfig.SHIP_PORTAL_RANGE_MIN, IPConfig.SHIP_PORTAL_RANGE_MAX))
                                        .executes(context -> {
                                            IPShipPortals.setRange(IntegerArgumentType.getInteger(context, "range"));
                                            return setMode(context, IPGlobal.ShipPortalMode.limited);
                                        })))
                        .then(Commands.literal("status").executes(IPShipPortalCommand::status))
                        .then(Commands.literal("mount")
                                .executes(context -> mount(context, true, pointed(context)))
                                .then(Commands.argument("portals", EntityArgument.entities())
                                        .executes(context -> mount(context, true, EntityArgument.getEntities(context, "portals")))))
                        .then(Commands.literal("unmount")
                                .executes(context -> mount(context, false, pointed(context)))
                                .then(Commands.argument("portals", EntityArgument.entities())
                                        .executes(context -> mount(context, false, EntityArgument.getEntities(context, "portals")))))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("light")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(IPShipPortalCommand::light)))
                                .then(Commands.literal("assemble-solid")
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(IPShipPortalCommand::assembleSolid))))
                                .then(Commands.literal("wand")
                                        .then(wandCorners("a", "b", "c", Commands.argument("dimension", DimensionArgument.dimension())
                                                .then(wandCorners("d", "e", "f", null)))))
                                .then(Commands.literal("disassemble")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .then(Commands.argument("goal", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("quarterTurns", IntegerArgumentType.integer(0, 3))
                                                                .executes(IPShipPortalCommand::disassemble))))))));
    }

    /** Three corner arguments of {@code debug wand}, then {@code next} (or the command's end). */
    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> wandCorners(
            final String a, final String b, final String c,
            @org.jetbrains.annotations.Nullable final com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> next) {
        final com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> last = Commands.argument(c, Vec3Argument.vec3(false));
        if (next == null) {
            last.executes(IPShipPortalCommand::wand);
        } else {
            last.then(next);
        }
        return Commands.argument(a, Vec3Argument.vec3(false)).then(Commands.argument(b, Vec3Argument.vec3(false)).then(last));
    }

    /** The portal the player running the command looks at (IP's own targeting), as a one-element list, or empty. */
    private static Collection<? extends Entity> pointed(final CommandContext<CommandSourceStack> context) {
        if (context.getSource().getEntity() instanceof final ServerPlayer player) {
            final Portal portal = PortalCommand.getPlayerPointingPortal(player, false);
            if (portal != null) {
                return List.of(portal);
            }
        }
        return List.of();
    }

    /**
     * {@code mount}: mounts each custom portal's side on the ship whose blocks it touches (docs/lessons/ship-mounted-portals.md).
     * {@code unmount}: leaves each mounted custom portal in the world where it is. One portal per cluster.
     */
    private static int mount(final CommandContext<CommandSourceStack> context, final boolean mount,
                             final Collection<? extends Entity> targets) {
        final List<Portal> portals = new ArrayList<>();
        final Set<UUID> seen = new HashSet<>();
        for (final Entity entity : targets) {
            if (entity instanceof final Portal portal && seen.add(portal.getUUID())) {
                portals.add(portal);
                PortalExtension.forClusterPortals(portal, p -> seen.add(p.getUUID()));
            }
        }
        if (portals.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No portal: look at one, or give portal entities"));
            return 0;
        }
        int done = 0;
        for (final Portal portal : portals) {
            final String refusal = mount ? IPShipPortalAnchors.mountCustom(portal) : IPShipPortalAnchors.unmountCustom(portal);
            if (refusal != null) {
                context.getSource().sendFailure(Component.literal("Portal " + portal.getUUID() + " not "
                        + (mount ? "mounted: " : "unmounted: ") + refusal));
                continue;
            }
            done++;
            final String text = mount
                    ? "Portal " + portal.getUUID() + " mounted: " + IPShipPortalAnchors.describeEnds(portal)
                    : "Portal " + portal.getUUID() + " unmounted; it stays where it is";
            context.getSource().sendSuccess(() -> Component.literal(text), true);
        }
        return done;
    }

    private static int setMode(final CommandContext<CommandSourceStack> context, final IPGlobal.ShipPortalMode mode) {
        IPShipPortals.setMode(mode);
        return status(context);
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        final String mode = switch (IPShipPortals.mode()) {
            case off -> "off";
            case limited -> "on, limited to a range of " + IPConfig.getConfig().shipPortalRangeChunks + " chunks";
            case unlimited -> "on";
        };
        final String text = "Ship-mounted portals: " + mode
                + (IPShipPortals.propertyOverride() != null ? " (set by -D" + IPShipPortals.ENABLE_PROPERTY + ")" : "");
        context.getSource().sendSuccess(() -> Component.literal(text), true);
        for (final String line : IPShipPortalAnchors.describe()) {
            context.getSource().sendSuccess(() -> Component.literal("Mounted portal " + line), true);
        }
        return IPShipPortals.isEnabled() ? 1 : 0;
    }

    /**
     * Test hook: the portal wand's portal creation ({@link PortalWandInteraction#createFromProtoPortal}, the server half)
     * with the corners a, b, c (left bottom, right bottom, left top) of the first side in the command's level and d, e, f
     * of the second in {@code dimension}, given in world coordinates. A side whose first corner is on a ship gets its
     * corners in that ship's plot, as the wand's client sends them ({@code IPShipPortalWandClient}).
     */
    private static int wand(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ProtoPortal proto = new ProtoPortal();
        proto.firstSide = wandSide(source.getLevel(), Vec3Argument.getVec3(context, "a"),
                Vec3Argument.getVec3(context, "b"), Vec3Argument.getVec3(context, "c"));
        proto.secondSide = wandSide(DimensionArgument.getDimension(context, "dimension"), Vec3Argument.getVec3(context, "d"),
                Vec3Argument.getVec3(context, "e"), Vec3Argument.getVec3(context, "f"));
        final boolean made = PortalWandInteraction.createFromProtoPortal(proto,
                message -> source.sendSuccess(() -> Component.literal("Wand: ").append(message), true));
        return made ? 1 : 0;
    }

    private static ProtoPortalSide wandSide(final ServerLevel level, final Vec3 leftBottom, final Vec3 rightBottom, final Vec3 leftTop) {
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(new AABB(leftBottom, leftBottom).inflate(0.01)))) {
            final Pose3dc pose = ship.logicalPose();
            if (Sable.HELPER.getContaining(level, pose.transformPositionInverse(leftBottom)) == ship) {
                final ProtoPortalSide side = new ProtoPortalSide(level.dimension(), pose.transformPositionInverse(leftBottom));
                side.rightBottom = pose.transformPositionInverse(rightBottom);
                side.leftTop = pose.transformPositionInverse(leftTop);
                return side;
            }
        }
        final ProtoPortalSide side = new ProtoPortalSide(level.dimension(), leftBottom);
        side.rightBottom = rightBottom;
        side.leftTop = leftTop;
        return side;
    }

    /**
     * Test hook: disassembles the ship whose block is at world position {@code pos} the way Create Aeronautics' physics
     * assembler does ({@code SimAssemblyHelper.disassembleSubLevel}), putting that block at {@code goal}, turned by
     * {@code quarterTurns} clockwise. Needs Simulated.
     */
    private static int disassemble(final CommandContext<CommandSourceStack> context) {
        if (!ModList.get().isLoaded("simulated")) {
            context.getSource().sendFailure(Component.literal("Needs Simulated (Create Aeronautics)"));
            return 0;
        }
        final ServerLevel level = context.getSource().getLevel();
        final BlockPos world = BlockPosArgument.getBlockPos(context, "pos");
        final BlockPos goal = BlockPosArgument.getBlockPos(context, "goal");
        final Rotation rotation = Rotation.values()[IntegerArgumentType.getInteger(context, "quarterTurns")];
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(world))) {
            final Vector3d local = ship.logicalPose().transformPositionInverse(
                    new Vector3d(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5));
            final BlockPos plot = BlockPos.containing(local.x, local.y, local.z);
            if (Sable.HELPER.getContaining(level, plot) != ship || level.getBlockState(plot).isAir()) {
                continue;
            }
            dev.simulated_team.simulated.util.SimAssemblyHelper.disassembleSubLevel(level, ship, plot, goal, rotation, false);
            context.getSource().sendSuccess(() -> Component.literal(
                    "Disassembled ship " + ship.getUniqueId() + " from plot " + plot.toShortString() + " to " + goal.toShortString()), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal("No ship block at " + world.toShortString()));
        return 0;
    }

    /**
     * Test hook: assembles the blocks in a box the way Create Aeronautics' assemblers select them (Create's contraption
     * search leaves out blocks without collision, such as portal blocks) through Sable's assembly.
     */
    private static int assembleSolid(final CommandContext<CommandSourceStack> context) {
        final ServerLevel level = context.getSource().getLevel();
        final BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        final BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        final java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
        for (final BlockPos pos : BlockPos.betweenClosed(from, to)) {
            final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(level, pos).isEmpty()) {
                blocks.add(pos.immutable());
            }
        }
        if (blocks.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Nothing solid to assemble"));
            return 0;
        }
        final dev.ryanhcode.sable.companion.math.BoundingBox3i bounds = dev.ryanhcode.sable.companion.math.BoundingBox3i.from(blocks);
        dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level, blocks.get(0), blocks, bounds);
        context.getSource().sendSuccess(() -> Component.literal("Assembled " + blocks.size() + " solid blocks"), true);
        return 1;
    }

    /**
     * Test hook: places fire on the ship block that is at the given world position, as flint and steel would (headless
     * tests cannot click). Fire placed in a ship's plot lights a portal frame through the same path as in the world.
     */
    private static int light(final CommandContext<CommandSourceStack> context) {
        final ServerLevel level = context.getSource().getLevel();
        final BlockPos world = BlockPosArgument.getBlockPos(context, "pos");
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(world))) {
            final Vector3d local = ship.logicalPose().transformPositionInverse(
                    new Vector3d(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5));
            final BlockPos plot = BlockPos.containing(local.x, local.y, local.z);
            if (Sable.HELPER.getContaining(level, plot) != ship) {
                continue;
            }
            if (!level.getBlockState(plot).isAir()) {
                context.getSource().sendFailure(Component.literal("Not air on the ship at " + plot.toShortString()));
                return 0;
            }
            level.setBlockAndUpdate(plot, BaseFireBlock.getState(level, plot));
            context.getSource().sendSuccess(() -> Component.literal(
                    "Fire placed on ship " + ship.getUniqueId() + " at plot " + plot.toShortString()), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal("No ship at " + world.toShortString()));
        return 0;
    }
}
