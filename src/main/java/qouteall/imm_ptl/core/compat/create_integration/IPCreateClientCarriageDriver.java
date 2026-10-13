/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Client side: elects one {@link CarriageContraptionEntity} per {@link Carriage} per client tick
 * to apply the server's carriage sync and the wheel animation.
 * <p>
 * Create keeps one carriage entity per dimension the carriage spans, all bound to the same
 * client-side {@link Carriage}. In vanilla only the player's dimension ticks, so only one copy
 * runs the sync. IP also ticks the portal-side client world, so while a carriage straddles a
 * portal both copies tick every client tick and each moves the shared travelling points by the
 * same sync step: the points advance twice as fast as the server, overshoot the snapshot, and
 * the next sync corrects back the other way. The portal seam (hidden slice) then flips sides
 * every sync interval and the wheels spin twice as fast. The first copy to tick in a client tick
 * (the player's own dimension ticks first) drives; the others only align to the shared anchors.
 */
public final class IPCreateClientCarriageDriver {
    private static int clientTick;
    private static boolean registered;
    private static final Map<Carriage, Claim> CLAIMS = new WeakHashMap<>();

    private IPCreateClientCarriageDriver() {
    }

    private static final class Claim {
        int tick;
        CarriageContraptionEntity driver;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, event -> clientTick++);
    }

    /** True when {@code entity} is (or becomes) the copy that drives {@code carriage} this tick. */
    public static boolean drives(Carriage carriage, CarriageContraptionEntity entity) {
        if (carriage == null || entity == null) {
            return true;
        }
        Claim claim = CLAIMS.computeIfAbsent(carriage, c -> new Claim());
        if (claim.tick != clientTick || claim.driver == null || claim.driver.isRemoved()) {
            claim.tick = clientTick;
            claim.driver = entity;
            return true;
        }
        return claim.driver == entity;
    }

    /**
     * True when another copy drove {@code carriage} this client tick or the last one, so the carriage's shared
     * anchors (all dimensions' copies) are current even for a copy that has no sync data of its own yet.
     */
    public static boolean isDrivenByOther(Carriage carriage, CarriageContraptionEntity entity) {
        Claim claim = CLAIMS.get(carriage);
        return claim != null && claim.driver != null && claim.driver != entity && !claim.driver.isRemoved()
            && clientTick - claim.tick <= 1;
    }
}
