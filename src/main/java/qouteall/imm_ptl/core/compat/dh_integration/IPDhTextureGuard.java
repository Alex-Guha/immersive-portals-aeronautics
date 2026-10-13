/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps Distant Horizons' per-frame textures intact across Immersive Portals' portal rendering.
 * <p>
 * DH draws its LODs into its own colour and depth textures and later, after the translucent layer,
 * blends them into the border between vanilla terrain and LODs ("vanilla fade"). IP renders the
 * portals before the translucent layer, and each portal pass renders its own LODs into the same
 * textures, so the outer world's fade read the portal world's LODs: nether colours bled into the
 * overworld's terrain border. Around the outermost portal loop the active textures are copied
 * aside and copied back afterwards, and DH's notion of which textures are active is restored.
 * <p>
 * No DH types here; {@link IPDhTextureGuardImpl} is only touched when DH is loaded.
 */
public final class IPDhTextureGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Boolean dhLoaded;
    private static boolean failed;

    private IPDhTextureGuard() {
    }

    /** Call before rendering the portals of a pass. Returns true if {@link #afterPortalRendering} must follow. */
    public static boolean beforePortalRendering() {
        if (!IPDhPortalView.ENABLED || failed || PortalRendering.isRendering() || !isDhLoaded()) {
            return false;
        }
        try {
            return IPDhTextureGuardImpl.save();
        } catch (Throwable e) {
            failed = true;
            LOGGER.warn("[IPDhPortalView] could not preserve DH textures across portal rendering; disabled: {}", e.toString());
            return false;
        }
    }

    public static void afterPortalRendering() {
        try {
            IPDhTextureGuardImpl.restore();
        } catch (Throwable e) {
            failed = true;
            LOGGER.warn("[IPDhPortalView] could not restore DH textures after portal rendering; disabled: {}", e.toString());
        }
    }

    private static boolean isDhLoaded() {
        if (dhLoaded == null) {
            dhLoaded = ModList.get() != null && ModList.get().isLoaded("distanthorizons");
        }
        return dhLoaded;
    }
}
