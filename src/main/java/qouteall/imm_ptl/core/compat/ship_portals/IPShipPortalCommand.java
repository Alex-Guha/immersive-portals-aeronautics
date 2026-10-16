/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

/**
 * {@code /ip_aeronautics shipportals on|off|status} and the test hooks
 * {@code /ip_aeronautics shipportals debug light <pos>} and {@code debug disassemble <pos> <goal> <quarterTurns>}
 * (operators only). Registered only with Sable present.
 */
public final class IPShipPortalCommand {
    private IPShipPortalCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ip_aeronautics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("shipportals")
                        .then(Commands.literal("on").executes(context -> setEnabled(context, true)))
                        .then(Commands.literal("off").executes(context -> setEnabled(context, false)))
                        .then(Commands.literal("status").executes(IPShipPortalCommand::status))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("light")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(IPShipPortalCommand::light)))
                                .then(Commands.literal("assemble-solid")
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(IPShipPortalCommand::assembleSolid))))
                                .then(Commands.literal("disassemble")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .then(Commands.argument("goal", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("quarterTurns", IntegerArgumentType.integer(0, 3))
                                                                .executes(IPShipPortalCommand::disassemble))))))));
    }

    private static int setEnabled(final CommandContext<CommandSourceStack> context, final boolean on) {
        IPShipPortals.setEnabled(on);
        return status(context);
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        final Boolean forced = IPShipPortals.propertyOverride();
        final String text = "Ship-mounted portals: " + (IPShipPortals.isEnabled() ? "on" : "off")
                + (forced != null ? " (set by -D" + IPShipPortals.ENABLE_PROPERTY + ")" : "");
        context.getSource().sendSuccess(() -> Component.literal(text), true);
        for (final String line : IPShipPortalAnchors.describe()) {
            context.getSource().sendSuccess(() -> Component.literal("Mounted portal " + line), true);
        }
        return IPShipPortals.isEnabled() ? 1 : 0;
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
