/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ducks.IEEntity;

/**
 * Rope packets for a world seen through a portal arrive dimension-tagged, and IP handles them with
 * {@code Minecraft.level} switched to that world ({@code MixinRopeStrandHolder_PortalViewers}), but Simulated's own
 * handlers, and other mods hooking them, look the holder up in {@code context.player().level()}, the player's own
 * world: Simulated's {@code ClientboundRopeDataPacket} and {@code ClientboundRopeStoppedPacket}, Joy of Creation 1.2.1
 * ({@code ClientboundRopeDataPacketMixin.joc$routeExtraStrand} at HEAD, which takes over the packet for its multi-rope
 * holders, and {@code ClientboundRopeStoppedPacketMixin} at TAIL; decompiled). While the player's world has no block
 * entity at that plot position JoC steps aside and the packet works; once it has one (a ship's old copy, left on the
 * client after the ship crossed out of the player's world, or another ship on the same plot) JoC gives that block entity
 * the other world's rope, which freezes there on the wrong world's clock until the ship is handed back or moves.
 * <p>
 * So while such a packet is handled the player is in the packet's world, put back right after. Nothing ticks in
 * between; only the handler and the mods hooking it run.
 */
public final class IPRopePacketLevel {
    private IPRopePacketLevel() {
    }

    public static void run(final Runnable handler) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;
        final ClientLevel packetLevel = minecraft.level;
        if (player == null || packetLevel == null || player.level() == packetLevel) {
            handler.run();
            return;
        }
        final Level playerLevel = player.level();
        ((IEEntity) player).ip_setWorld(packetLevel);
        try {
            handler.run();
        } finally {
            ((IEEntity) player).ip_setWorld(playerLevel);
        }
    }
}
