/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import java.lang.reflect.Field;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Keeps Sable's per-move collision state valid when IP answers a collision itself.
 * <p>
 * Sable redirects the {@code Entity.collide} call inside {@code Entity.move} and stores a
 * {@code CollisionInfo} in the entity ({@code sable$collisionInfo}); its later hooks in the same
 * {@code move} call read that object without a null check. IP wraps the same call
 * ({@code MixinEntity.redirectHandleCollisions}) and, when the entity touches a portal (or in two
 * safety branches), returns its own result without invoking the wrapped call, so Sable's redirect
 * never runs. On an entity's very first move that leaves the field null and the move crashes
 * ("Cannot read field horizontalCollision because sable$collisionInfo is null"); on later moves it
 * leaves stale flags. This records IP's outcome in Sable's field instead.
 * <p>
 * The field is Sable's private mixin-added field, reached by reflection once; without Sable, or if
 * the lookup fails, this is a no-op.
 */
public final class IPSableCollisionBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPSableCollision] ";
    private static final String FIELD_NAME = "sable$collisionInfo";

    private static volatile Field collisionInfoField;
    private static volatile boolean disabled;
    private static boolean resolved;

    private IPSableCollisionBridge() {
    }

    /**
     * Records the outcome of a move whose {@code Entity.collide} call IP answered without calling
     * the wrapped (Sable-redirected) method.
     *
     * @param minorHorizontalCollision {@code Entity.isHorizontalCollisionMinor(result)}, evaluated
     *                                 by the caller because the method is protected
     */
    public static void recordBypassedCollision(
        Entity entity, Vec3 attemptedMove, Vec3 result, boolean minorHorizontalCollision
    ) {
        if (disabled) {
            return;
        }
        Field field = collisionInfoField;
        if (field == null) {
            field = resolve();
            if (field == null) {
                return;
            }
        }
        try {
            Writer.write(field, entity, attemptedMove, result, minorHorizontalCollision);
        } catch (Throwable t) {
            disabled = true;
            LOGGER.warn(TAG + "cannot record collision outcome for Sable; disabling: {}", String.valueOf(t));
        }
    }

    private static synchronized Field resolve() {
        if (resolved) {
            return collisionInfoField;
        }
        resolved = true;
        if (!ModList.get().isLoaded("sable")) {
            disabled = true;
            return null;
        }
        try {
            Field field = Entity.class.getDeclaredField(FIELD_NAME);
            field.setAccessible(true);
            collisionInfoField = field;
            LOGGER.debug(TAG + "active; IP collision outcomes are recorded in Entity.{}", FIELD_NAME);
        } catch (Throwable t) {
            disabled = true;
            LOGGER.warn(TAG + "Entity.{} not found; Sable may crash on an entity's first move through a portal: {}",
                FIELD_NAME, String.valueOf(t));
        }
        return collisionInfoField;
    }

    /** Separate class so Sable's types are only loaded when Sable is present. */
    private static final class Writer {
        static void write(
            Field field, Entity entity, Vec3 attemptedMove, Vec3 result, boolean minorHorizontalCollision
        ) throws IllegalAccessException {
            SubLevelEntityCollision.CollisionInfo info = new SubLevelEntityCollision.CollisionInfo();
            info.motion = attemptedMove;
            info.preDeltaMovement = entity.getDeltaMovement();
            info.horizontalCollision = !Mth.equal(attemptedMove.x, result.x) || !Mth.equal(attemptedMove.z, result.z);
            info.verticalCollision = attemptedMove.y != result.y;
            info.verticalCollisionBelow = info.verticalCollision && attemptedMove.y < 0.0;
            info.minorHorizontalCollision = info.horizontalCollision && minorHorizontalCollision;
            field.set(entity, info);
        }
    }
}
