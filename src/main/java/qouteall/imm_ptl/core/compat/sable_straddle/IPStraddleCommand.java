/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import qouteall.imm_ptl.core.compat.IPMixinReport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipJoints;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRopeCompat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsBackend;

/**
 * {@code /ip_aeronautics straddle on|off|status}, {@code /ip_aeronautics straddle debug on|off} and the test hooks
 * {@code /ip_aeronautics straddle debug rope <from> <to>}, {@code debug swivel} and {@code debug joints} (operators
 * only).
 * Registered only with Sable present.
 */
public final class IPStraddleCommand {
    private IPStraddleCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ip_aeronautics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("compat").executes(IPStraddleCommand::compat))
                .then(Commands.literal("straddle")
                        .then(Commands.literal("on").executes(context -> setEnabled(context, true)))
                        .then(Commands.literal("off").executes(context -> setEnabled(context, false)))
                        .then(Commands.literal("status").executes(IPStraddleCommand::status))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("on").executes(context -> setDebug(context, true)))
                                .then(Commands.literal("off").executes(context -> setDebug(context, false)))
                                .then(Commands.literal("rope")
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(IPStraddleCommand::tieRope))))
                                .then(Commands.literal("swivel").executes(IPStraddleCommand::assembleSwivels))
                                .then(Commands.literal("dock").executes(IPStraddleCommand::dockConnectors))
                                .then(Commands.literal("joints").executes(IPStraddleCommand::listJoints)))));
    }

    /** Which of our hooks in other mods are alive, as far as their classes have loaded. */
    private static int compat(final CommandContext<CommandSourceStack> context) {
        for (final String line : IPMixinReport.summary()) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int setEnabled(final CommandContext<CommandSourceStack> context, final boolean on) {
        IPStraddle.setEnabled(context.getSource().getServer(), on);
        return status(context);
    }

    private static int setDebug(final CommandContext<CommandSourceStack> context, final boolean on) {
        IPStraddle.setDebugOutline(on);
        return status(context);
    }

    /** Test hook: ties the rope holders at two positions (on ships or in the world) with a rope. */
    private static int tieRope(final CommandContext<CommandSourceStack> context) {
        final BlockPos from = BlockPosArgument.getBlockPos(context, "from");
        final BlockPos to = BlockPosArgument.getBlockPos(context, "to");
        final String error = IPShipRopeCompat.tie(context.getSource().getLevel(), from, to);
        if (error != null) {
            context.getSource().sendFailure(Component.literal("No rope: " + error));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Tied " + from.toShortString() + " to " + to.toShortString()), true);
        return 1;
    }

    /** Test hook: assembles every unassembled swivel bearing on the ships of the command's dimension. */
    private static int assembleSwivels(final CommandContext<CommandSourceStack> context) {
        final int count = IPShipJoints.assembleSwivelBearings(context.getSource().getLevel());
        context.getSource().sendSuccess(() -> Component.literal("Assembling " + count + " swivel bearing(s)"), true);
        return count;
    }

    /** Test hook: docks the first two docking connectors on the ships of the command's dimension. */
    private static int dockConnectors(final CommandContext<CommandSourceStack> context) {
        final boolean done = IPShipJoints.dockConnectors(context.getSource().getLevel());
        context.getSource().sendSuccess(() -> Component.literal(done ? "Docked two connectors" : "No two connectors to dock"), true);
        return done ? 1 : 0;
    }

    /** Test hook: lists the ships of the command's dimension joined to other ships, with their partners. */
    private static int listJoints(final CommandContext<CommandSourceStack> context) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(context.getSource().getLevel());
        int jointed = 0;
        if (container != null) {
            for (final ServerSubLevel ship : container.getAllSubLevels()) {
                final var partners = IPShipJoints.partners(ship);
                if (!partners.isEmpty()) {
                    jointed++;
                    final String line = "Ship " + ship.getUniqueId() + " jointed to " + partners;
                    context.getSource().sendSuccess(() -> Component.literal(line), true);
                }
                final var connected = IPShipJoints.connected(ship);
                if (!connected.isEmpty()) {
                    final String line = "Ship " + ship.getUniqueId() + " connected to " + connected;
                    context.getSource().sendSuccess(() -> Component.literal(line), true);
                }
            }
            for (final String line : IPShipJoints.describeSprings(context.getSource().getLevel())) {
                context.getSource().sendSuccess(() -> Component.literal(line), true);
            }
        }
        final int total = jointed;
        context.getSource().sendSuccess(() -> Component.literal(total + " jointed ship(s) in "
                + context.getSource().getLevel().dimension().location()), true);
        return total;
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        final boolean backend = IPPhysicsBackend.isOwnBackendActive();
        final String text = "Ship straddling: " + (IPStraddle.isEnabled() ? "on" : "off")
                + (backend ? "" : " (inactive: the ipa_rapier physics backend is not running, ships use the one-step crossing)")
                + ", debug outline: " + (IPStraddle.isDebugOutline() ? "on" : "off")
                + ", sessions: " + IPStraddleSessions.keys().size();
        context.getSource().sendSuccess(() -> Component.literal(text), true);
        return IPStraddle.isAvailable() ? 1 : 0;
    }
}
