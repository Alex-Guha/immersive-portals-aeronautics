/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRopeCompat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsPipelineProvider;

/**
 * {@code /ip_aeronautics straddle on|off|status}, {@code /ip_aeronautics straddle debug on|off} and the test hook
 * {@code /ip_aeronautics straddle debug rope <from> <to>} (operators only).
 * Registered only with Sable present.
 */
public final class IPStraddleCommand {
    private IPStraddleCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ip_aeronautics")
                .requires(source -> source.hasPermission(2))
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
                                                        .executes(IPStraddleCommand::tieRope)))))));
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

    private static int status(final CommandContext<CommandSourceStack> context) {
        final boolean backend = IPPhysicsPipelineProvider.isOwnBackendActive();
        final String text = "Ship straddling: " + (IPStraddle.isEnabled() ? "on" : "off")
                + (backend ? "" : " (inactive: the ipa_rapier physics backend is not running, ships use the one-step crossing)")
                + ", debug outline: " + (IPStraddle.isDebugOutline() ? "on" : "off")
                + ", sessions: " + IPStraddleSessions.keys().size();
        context.getSource().sendSuccess(() -> Component.literal(text), true);
        return IPStraddle.isAvailable() ? 1 : 0;
    }
}
