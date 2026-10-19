/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.portal.global_portals;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Keeps other mods' "fell out of the world" teleports away from entities crossing a dimension stack's floor or
 * ceiling ({@link VerticalConnectingPortal}).
 * <p>
 * The Aether sends every player whose feet are at or below its minimum build height to the top of the overworld,
 * keeping their motion. IP moves a player through a vertical connector when their eyes cross the plane, so a player
 * going up from the overworld into an Aether stacked above it arrives with their feet 1.6 blocks under the Aether's
 * floor: the Aether sent them straight back down, still moving up, into the overworld's ceiling and the Aether again,
 * a "Descending from the Aether" loop. Falling out of the Aether's bottom is the connector's job anyway, and IP does
 * it seamlessly and with the stack's offset, scale and rotation.
 * <p>
 * So a dimension change requested through {@code Entity.changeDimension} (NeoForge's travel event, which IP's own
 * crossings never fire) is cancelled while the entity is partly through a vertical connector of its world that can
 * teleport it: feet at or below a floor, head at or above a ceiling. Cancelled at the highest priority, so listeners
 * of the travel itself (the Aether's loading-screen text) do not see it either.
 */
public final class VerticalConnectorTravelGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long LOG_INTERVAL_TICKS = 100;
    private static final Map<Entity, Long> LAST_LOGGED = new WeakHashMap<>();

    private VerticalConnectorTravelGuard() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, EntityTravelToDimensionEvent.class, VerticalConnectorTravelGuard::onTravel
        );
    }

    private static void onTravel(EntityTravelToDimensionEvent event) {
        if (blocksTravel(event.getEntity(), event.getDimension())) {
            event.setCanceled(true);
        }
    }

    /**
     * Whether a dimension change of {@code entity} to {@code target} requested by another mod must not happen (the
     * entity is partly through a vertical connector); logs it. Also called at the head of
     * {@code ServerPlayer.changeDimension} ({@code MixinServerPlayerEntity_MA}), before IP's own bookkeeping for a
     * conventional dimension change, which runs ahead of NeoForge's travel event: that removed the player from all
     * chunk and entity trackers on every tick the Aether tried to send them down, freezing them between the worlds.
     */
    public static boolean blocksTravel(Entity entity, ResourceKey<Level> target) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        Portal connector = connectorBeingCrossed(level, entity);
        if (connector == null) {
            return false;
        }

        long now = level.getGameTime();
        Long last = LAST_LOGGED.get(entity);
        if (last == null || now - last >= LOG_INTERVAL_TICKS) {
            LAST_LOGGED.put(entity, now);
            LOGGER.info(
                "[IPVerticalConnector] {} at y {} in {} is crossing the {} into {}: cancelled another mod's teleport to {}",
                entity.getName().getString(), String.format("%.2f", entity.getY()), level.dimension().location(),
                connector.getNormal().y > 0 ? "floor" : "ceiling", connector.getDestDim().location(),
                target.location()
            );
        }
        return true;
    }

    /** The vertical connector of {@code level} the entity is partly through, or null. */
    private static @Nullable Portal connectorBeingCrossed(Level level, Entity entity) {
        AABB box = entity.getBoundingBox();
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (!(portal instanceof VerticalConnectingPortal) || !portal.canTeleportEntity(entity)) {
                continue;
            }
            double planeY = portal.getOriginPos().y;
            boolean floor = portal.getNormal().y > 0;
            if (floor ? box.minY <= planeY : box.maxY >= planeY) {
                return portal;
            }
        }
        return null;
    }
}
