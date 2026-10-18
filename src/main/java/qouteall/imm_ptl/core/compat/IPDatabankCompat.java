/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Databank (Halcyon's library) saves the view matrix for its post-processing effects at the start of every
 * {@code LevelRenderer.renderLevel} ({@code PostShaderManager.viewStackMatrix}, read back as {@code invViewMat}).
 * IP's portal passes call {@code renderLevel} inside the main one, so the main pass's effects, which run at its end,
 * would rebuild world positions with the last portal camera's matrix whenever a portal is on screen. The portal pass
 * puts the main pass's matrix back when it ends.
 */
public class IPDatabankCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;
    private static @Nullable Field viewStackMatrixField = null;

    private static @Nullable Field getField() {
        if (!initialized) {
            initialized = true;
            if (ModList.get().isLoaded("databank")) {
                try {
                    viewStackMatrixField = Class.forName("com.cmdpro.databank.shaders.PostShaderManager")
                        .getField("viewStackMatrix");
                }
                catch (ReflectiveOperationException | LinkageError e) {
                    LOGGER.warn("[IPDatabank] Databank's view matrix not found; its effects may misplace near portals", e);
                }
            }
        }
        return viewStackMatrixField;
    }

    public static @Nullable Object beforePortalPass() {
        Field field = getField();
        if (field == null) {
            return null;
        }
        try {
            return field.get(null);
        }
        catch (IllegalAccessException e) {
            viewStackMatrixField = null;
            return null;
        }
    }

    public static void afterPortalPass(@Nullable Object saved) {
        Field field = viewStackMatrixField;
        if (field == null || saved == null) {
            return;
        }
        try {
            field.set(null, saved);
        }
        catch (IllegalAccessException | IllegalArgumentException e) {
            viewStackMatrixField = null;
        }
    }
}
