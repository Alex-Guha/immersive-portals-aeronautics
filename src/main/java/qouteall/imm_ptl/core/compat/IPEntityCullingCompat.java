/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * EntityCulling (tr7zw) in portal views.
 * <p>
 * EntityCulling decides once per client tick, from the player's camera, which entities and block entities are hidden
 * behind blocks ({@code Cullable.setCulled}), and then skips drawing them in every pass ({@code LevelRenderer.renderEntity}
 * and {@code BlockEntityRenderDispatcher.render} at HEAD) and gives culled entities a reduced tick. A portal view of the
 * player's own dimension draws the same entity objects from another camera, so whatever the player cannot see directly
 * vanished from the view, and came back whenever the test from the player's position changed; in a view of another
 * dimension, flags left from when the player was there did the same.
 * <p>
 * An entity found in view in a portal pass ({@code EntityRenderDispatcher.shouldRender}) is marked not culled and
 * forced visible for EntityCulling's own timeout (1 s): its cull thread leaves forced-visible entities alone, so it
 * draws and ticks normally while it keeps appearing in a portal view. Block entities are never reduced-ticked, so
 * during portal passes EntityCulling's own switch for them ({@code skipBlockEntityCulling}) is on. All by reflection:
 * EntityCulling is optional, and anything missing turns this off with one warning.
 */
public final class IPEntityCullingCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;
    private static @Nullable Class<?> cullable;
    private static @Nullable MethodHandle setCulled;
    private static @Nullable MethodHandle setTimeout;
    private static @Nullable Field modInstance;
    private static @Nullable Field modConfig;
    private static @Nullable Field skipBlockEntityCulling;

    /** The config object whose switch was turned on for the portal passes, and the value it had. */
    private static @Nullable Object switchedConfig;
    private static boolean savedSkipBlockEntityCulling;

    private IPEntityCullingCompat() {
    }

    private static boolean available() {
        if (!initialized) {
            initialized = true;
            if (ModList.get().isLoaded("entityculling")) {
                try {
                    cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable");
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    setCulled = lookup.findVirtual(cullable, "setCulled", MethodType.methodType(void.class, boolean.class))
                        .asType(MethodType.methodType(void.class, Object.class, boolean.class));
                    setTimeout = lookup.findVirtual(cullable, "setTimeout", MethodType.methodType(void.class))
                        .asType(MethodType.methodType(void.class, Object.class));
                    Class<?> base = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
                    modInstance = base.getField("instance");
                    modConfig = base.getField("config");
                    skipBlockEntityCulling = Class.forName("dev.tr7zw.entityculling.versionless.Config")
                        .getField("skipBlockEntityCulling");
                }
                catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.warn("[IPEntityCulling] EntityCulling's classes are not as expected; entities hidden by it "
                        + "may be missing in portal views", e);
                    cullable = null;
                }
            }
        }
        return cullable != null;
    }

    /** {@code entity} is in view in a portal pass: EntityCulling must not hide it there, nor slow its tick. */
    public static void onEntityInPortalView(Entity entity) {
        if (!available() || !cullable.isInstance(entity)) {
            return;
        }
        try {
            setCulled.invokeExact((Object) entity, false);
            setTimeout.invokeExact((Object) entity);
        }
        catch (Throwable e) {
            LOGGER.warn("[IPEntityCulling] marking an entity visible failed; not trying again", e);
            cullable = null;
        }
    }

    /** The first portal layer is pushed: block entities are drawn whatever EntityCulling decided for the player. */
    public static void onPortalPassesBegin() {
        if (!available() || switchedConfig != null) {
            return;
        }
        try {
            Object mod = modInstance.get(null);
            Object config = mod != null ? modConfig.get(mod) : null;
            if (config == null) {
                return;
            }
            savedSkipBlockEntityCulling = skipBlockEntityCulling.getBoolean(config);
            skipBlockEntityCulling.setBoolean(config, true);
            switchedConfig = config;
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[IPEntityCulling] switching block entity culling failed; not trying again", e);
            cullable = null;
        }
    }

    /** The last portal layer is popped: EntityCulling's switch goes back to the player's setting. */
    public static void onPortalPassesEnd() {
        Object config = switchedConfig;
        if (config == null) {
            return;
        }
        switchedConfig = null;
        try {
            skipBlockEntityCulling.setBoolean(config, savedSkipBlockEntityCulling);
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[IPEntityCulling] restoring block entity culling failed", e);
        }
    }
}
