/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * BadOptimizations' sky colour caching keeps one sky colour per {@code ClientLevel} ({@code bo$skyColorCache}, merged
 * into the class by its {@code MixinClientWorld}): filled by vanilla's computation while it is empty, then refreshed
 * only for the level that takes its single once-per-tick "changed" flag, which is the level the player is in (its fog
 * colour is asked for first each frame). A world seen through a portal kept the sky colour it had when the player left
 * it, whatever the time, weather or biome at the far portal. Each portal pass empties the viewed level's cache, so the
 * pass gets vanilla's colour, and puts the old value back when it ends: for a portal into the player's own world, the
 * main view's cached colour must not become the far portal's.
 */
public class IPBadOptimizationsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;
    private static @Nullable Field skyColorCacheField = null;

    private static @Nullable Field getField() {
        if (!initialized) {
            initialized = true;
            if (ModList.get().isLoaded("badoptimizations")) {
                try {
                    Field field = ClientLevel.class.getDeclaredField("bo$skyColorCache");
                    field.setAccessible(true);
                    skyColorCacheField = field;
                }
                catch (ReflectiveOperationException | RuntimeException e) {
                    // absent when its sky colour caching is turned off (the mixin is not applied)
                    LOGGER.info("[IPCompat] BadOptimizations' sky colour cache not found (caching off?): {}", e.toString());
                }
            }
        }
        return skyColorCacheField;
    }

    /** Empties the level's cached sky colour for a portal pass; returns the value to give back afterwards. */
    public static @Nullable Object beforePortalPass(ClientLevel level) {
        Field field = getField();
        if (field == null) {
            return null;
        }
        try {
            Object saved = field.get(level);
            field.set(level, null);
            return saved;
        }
        catch (IllegalAccessException | IllegalArgumentException e) {
            skyColorCacheField = null;
            return null;
        }
    }

    public static void afterPortalPass(ClientLevel level, @Nullable Object saved) {
        Field field = skyColorCacheField;
        if (field == null) {
            return;
        }
        try {
            field.set(level, saved);
        }
        catch (IllegalAccessException | IllegalArgumentException e) {
            skyColorCacheField = null;
        }
    }
}
