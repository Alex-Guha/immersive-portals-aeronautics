/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.ryanhcode.offroad.handlers.client.MultiMiningClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Client side of the cracks on blocks a borehead breaks through a portal ({@link IPBoreheadPortals}): Offroad's crack
 * progress for blocks of another dimension arrives dimension-tagged, and is applied and drawn in that dimension.
 */
public final class IPBoreheadPortalsClient {
    private static final boolean OFFROAD_LOADED = ModList.get().isLoaded("offroad");

    private IPBoreheadPortalsClient() {
    }

    /**
     * Offroad applies its crack progress to the level of its packet context, the player's own level, which Immersive
     * Portals' world switch for a dimension-tagged packet leaves alone.
     *
     * @return the world a dimension-tagged packet is being handled in, else {@code level}
     */
    public static Level packetLevel(final Level level) {
        if (level != null && level.isClientSide() && ClientWorldLoader.getIsWorldSwitched()) {
            final ClientLevel switched = Minecraft.getInstance().level;
            if (switched != null) {
                return switched;
            }
        }
        return level;
    }

    /**
     * Ticks Offroad's client mining handler (cracks, dust) in a world Immersive Portals ticks for its portal views
     * (called in that world's switch): Offroad ticks it from the level tick event, which fires only for the player's
     * own world.
     */
    public static void tickRemoteWorld(final ClientLevel world) {
        if (OFFROAD_LOADED) {
            Impl.tick(world);
        }
    }

    /** Touches Offroad's classes; loaded only when Offroad is present. */
    private static final class Impl {
        static void tick(final ClientLevel world) {
            MultiMiningClientHandler.tick(world);
        }
    }
}
