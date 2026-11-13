/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ScaleUtils;

/**
 * Portal scaling through Pehkui when Pehkui is installed. Without Pehkui, a portal that changes scale resizes an entity
 * through the vanilla {@code generic.scale} attribute ({@code ScaleUtils}, modifier {@code iportal:scaling}), which
 * scales the hitbox and eye height only: a player shrunk by a portal keeps full walking speed, jump, step height and
 * reach, so they seem to race about. With Pehkui, the portal's factor is a Pehkui scale type of its own
 * ({@code iportal:scaling}, {@link IPPehkuiScaleType}) multiplied into {@code pehkui:base}, from which Pehkui derives
 * size, motion, step height, jump, reach, falling and the rest. A scale of its own keeps the portal's share apart from
 * {@code /scale set pehkui:base} and from other mods writing BASE's base value (Create: Pocket Sized's Pym resizes riders
 * of a ship it scales that way, and its harness shrinks players), so the scales multiply instead of overwriting each
 * other.
 * <p>
 * The vanilla attribute and the Pehkui scale never both hold a portal's scaling: each entity that joins a level on the
 * server is moved to the backend in use ({@link #carryOver}), so a world played without Pehkui keeps its sizes when
 * Pehkui is added, and a player's size survives Pehkui being removed through {@link #MIRROR_TAG}.
 * <p>
 * This class names no Pehkui type, so it loads without Pehkui; Pehkui's classes are touched only through
 * {@link IPPehkuiScaleType}, and only once {@link #active()} is true.
 */
public final class IPPehkuiScaling {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A player's portal scaling while Pehkui holds it, in the player's NeoForge persistent data. Pehkui's own data is
     * dropped with Pehkui; this lets a world opened later without Pehkui put the scaling back on the vanilla attribute.
     * Players only: reading it on a mob's join would give every mob an empty NeoForge data tag.
     */
    public static final String MIRROR_TAG = "ip_aeronautics:portal_scaling";

    private static volatile boolean active;

    private IPPehkuiScaling() {
    }

    public static void init(IEventBus modEventBus) {
        if (ModList.get().isLoaded("pehkui")) {
            // the main thread: Pehkui's registries are plain maps, and mod constructors run in parallel
            modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(IPPehkuiScaling::register));
        }
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living) {
                carryOver(living);
            }
        });
    }

    private static void register() {
        try {
            IPPehkuiScaleType.register();
            active = true;
            LOGGER.info("[IPPehkui] portal scaling goes through Pehkui: scale type {} multiplied into pehkui:base",
                IPPehkuiScaleType.ID);
        }
        catch (Throwable t) {
            LOGGER.error("[IPPehkui] could not register a scale type with Pehkui; portal scaling stays on the vanilla "
                + "scale attribute (size only)", t);
        }
    }

    /** Pehkui is installed and holds portal scaling. */
    public static boolean active() {
        return active;
    }

    /** The portal scaling Pehkui holds for the entity (1 when none). Only while {@link #active()}. */
    public static double portalScaling(Entity entity) {
        return IPPehkuiScaleType.get(entity);
    }

    /** Sets the portal scaling at once (no easing); Pehkui refreshes the dimensions. Only while {@link #active()}. */
    public static void setPortalScaling(Entity entity, double scale) {
        IPPehkuiScaleType.set(entity, scale);
    }

    /** Pehkui's base scale, the portal's share included; 1 without Pehkui. */
    public static double baseScale(Entity entity) {
        return active ? IPPehkuiScaleType.base(entity) : 1.0;
    }

    /** Pehkui's third-person camera distance scale; 1 without Pehkui. */
    public static double thirdPersonScale(Entity entity) {
        return active ? IPPehkuiScaleType.thirdPerson(entity) : 1.0;
    }

    /** Pehkui's block reach scale; 1 without Pehkui. */
    public static double blockReachScale(Entity entity) {
        return active ? IPPehkuiScaleType.blockReach(entity) : 1.0;
    }

    /** Pehkui's motion scale; 1 without Pehkui. */
    public static double motionScale(Entity entity) {
        return active ? IPPehkuiScaleType.motion(entity) : 1.0;
    }

    /** Keeps {@link #MIRROR_TAG} equal to the portal scaling Pehkui holds for a player (server side). */
    static void mirror(Entity entity, double scale) {
        if (!(entity instanceof Player) || entity.level() == null || entity.level().isClientSide()) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        if (scale == 1.0) {
            data.remove(MIRROR_TAG);
        }
        else {
            data.putDouble(MIRROR_TAG, scale);
        }
    }

    /**
     * Moves an entity's portal scaling to the backend in use. With Pehkui, an {@code iportal:scaling} attribute
     * modifier (from a session without Pehkui) is multiplied into the Pehkui scale and removed. Without Pehkui, a
     * player's {@link #MIRROR_TAG} (from a session with Pehkui, whose data is gone) becomes the attribute modifier.
     */
    private static void carryOver(LivingEntity entity) {
        if (active) {
            double vanilla = ScaleUtils.getAttributePortalScaling(entity);
            if (vanilla != 1.0) {
                double before = IPPehkuiScaleType.get(entity);
                ScaleUtils.setAttributePortalScaling(entity, 1.0);
                IPPehkuiScaleType.set(entity, before * vanilla);
                LOGGER.info("[IPPehkui] {}: portal scaling {} moved from the vanilla scale attribute to Pehkui ({})",
                    entity.getName().getString(), vanilla, before * vanilla);
            }
        }
        else if (entity instanceof Player) {
            CompoundTag data = entity.getPersistentData();
            if (data.contains(MIRROR_TAG, Tag.TAG_DOUBLE)) {
                double scale = data.getDouble(MIRROR_TAG);
                data.remove(MIRROR_TAG);
                if (ScaleUtils.getAttributePortalScaling(entity) == 1.0 && scale > 0 && scale != 1.0) {
                    ScaleUtils.setAttributePortalScaling(entity, scale);
                    LOGGER.info("[IPPehkui] {}: portal scaling {} restored on the vanilla scale attribute (Pehkui "
                        + "is no longer installed)", entity.getName().getString(), scale);
                }
            }
        }
    }
}
