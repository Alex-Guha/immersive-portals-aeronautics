/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipJoints;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsPipelineProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Ships straddling portals (docs/lessons/straddling.md). Entry points for the rest of the mod; only called with Sable
 * present.
 * <p>
 * {@link #isAvailable()} (D4) is true when our physics backend runs every level and straddling is switched on. Then
 * {@link #tick} replaces the one-step crossing scan; otherwise that scan runs exactly as before. On by default
 * (since 1.12.0); {@code /ip_aeronautics straddle off} or {@code -Dip_aeronautics.straddle=false} turns it off.
 */
public final class IPStraddle {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "ip_aeronautics.straddle";

    /** On by default; {@code -Dip_aeronautics.straddle=false} or {@code /ip_aeronautics straddle off} turns it off. */
    private static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY));
    private static volatile boolean debugOutline;

    private IPStraddle() {
    }

    public static boolean isAvailable() {
        return enabled && IPPhysicsPipelineProvider.isOwnBackendActive();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches straddling on or off. Off closes every session (ships mid-straddle go back to the one-step crossing).
     */
    public static void setEnabled(final MinecraftServer server, final boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (!on) {
            IPStraddleSessions.closeAll("straddling switched off");
            IPStraddleController.releaseFrames(server);
            IPPortalRims.clearAll();
            IPShipCrossingDetector.reset();
            // no straddle tick runs while off: retract the sessions from clients now
            IPStraddleSync.tick(server);
        }
        LOGGER.info("[IPStraddle] straddling switched {}", on ? "on" : "off");
    }

    public static boolean isDebugOutline() {
        return debugOutline;
    }

    public static void setDebugOutline(final boolean on) {
        debugOutline = on;
    }

    /**
     * A server starts or stops: forgets every session and trail (its physics scenes are or will be gone).
     */
    public static void clearState() {
        IPStraddleSessions.reset();
        IPShipCrossingDetector.reset();
        IPStraddleController.reset();
        IPShipJoints.reset();
        IPPortalRims.reset();
        IPStraddleDebugTrace.reset();
        IPStraddleSync.reset();
        IPStraddleSeats.clearState();
        IPStraddleShields.reset();
    }

    /** A server's first tick. */
    public static void onServerStarted() {
        if (!IPPhysicsPipelineProvider.isOwnBackendActive()) {
            LOGGER.info("[IPStraddle] ships cannot straddle portals: the ipa_rapier physics backend is not active");
        } else {
            LOGGER.info("[IPStraddle] ships straddling portals: {} (/ip_aeronautics straddle on|off)", enabled ? "on" : "off");
        }
    }

    public static void tick(final MinecraftServer server, final long tick) {
        IPStraddleDebugTrace.tick(server, tick);
        IPStraddleController.tick(server, tick);
        IPStraddleSeats.tick(server);
        IPStraddleSync.tick(server);
        if (debugOutline) {
            IPStraddleDebugOutline.draw(tick);
        }
    }

    /**
     * @return the world regions in {@code level} where straddling ships have images (their bounds mapped through the
     * portal, grown by 2 blocks), whose terrain must be in {@code level}'s physics chart
     */
    public static List<AABB> imageRegionsIn(final ServerLevel level) {
        final List<AABB> regions = new ArrayList<>();
        IPStraddleSessions.forEach(session -> {
            if (session.dest == level && !session.ship.isRemoved()) {
                regions.add(mapBounds(session.isometry, session.ship.boundingBox()).inflate(2.0));
            }
        });
        return regions;
    }

    /**
     * Players near the far half of {@code ship} in a destination dimension: they need the ship on their client to draw
     * and stand on that far half, whatever chunks they view through portals.
     */
    public static void forEachFarHalfViewer(final dev.ryanhcode.sable.sublevel.ServerSubLevel ship,
                                            final java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> viewer) {
        IPStraddleSessions.forEach(session -> {
            if (session.ship != ship || session.dest == session.source) {
                return;
            }
            final AABB area = mapBounds(session.isometry, ship.boundingBox()).inflate(FAR_HALF_VIEW_DISTANCE);
            for (final net.minecraft.server.level.ServerPlayer player : session.dest.players()) {
                if (area.contains(player.position())) {
                    viewer.accept(player);
                }
            }
        });
    }

    /** How far from a ship's far half players get the ship synced. */
    private static final double FAR_HALF_VIEW_DISTANCE = 96.0;

    private static AABB mapBounds(final IPPortalIsometry isometry, final BoundingBox3dc box) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = isometry.mapPoint(new Vec3(
                    (i & 1) == 0 ? box.minX() : box.maxX(),
                    (i & 2) == 0 ? box.minY() : box.maxY(),
                    (i & 4) == 0 ? box.minZ() : box.maxZ()));
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
