/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Routes block changes on Sable ships to the ship's render data when IP's own view area is in use (vanilla
 * renderer, no Sodium).
 * <p>
 * Sable learns that a ship section changed through an {@code @Inject} at the head of {@code ViewArea.setDirty}
 * (Sodium: {@code SodiumWorldRenderer.scheduleRebuildForChunk}). IP replaces each level renderer's view area with
 * {@code ImmPtlViewArea}, whose {@code setDirty} override never calls super, so without Sodium no block change,
 * light update or neighbour update ever reached a ship: placed blocks stayed invisible and broken ones stayed
 * drawn until F3+A or a resize of the ship. The ship is looked up in the view area's own level, not
 * {@code Minecraft.level} as Sable does, since plot coordinates repeat across the dimensions IP keeps loaded.
 */
public final class IPSableViewAreaDirty {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SABLE = ModList.get().isLoaded("sable");
    private static boolean logged;

    private IPSableViewAreaDirty() {
    }

    /**
     * Marks the section dirty in the render data of the ship owning it; false when the section is not on a ship,
     * in which case the caller marks its own section as usual.
     */
    public static boolean routeToShip(Level level, int cx, int cy, int cz, boolean playerChanged) {
        if (!SABLE || level == null
            || (Math.abs(cx) <= IPSableCompatHelper.PLOT_SPACE_CHUNK_THRESHOLD
            && Math.abs(cz) <= IPSableCompatHelper.PLOT_SPACE_CHUNK_THRESHOLD)) {
            return false;
        }
        return Impl.route(level, cx, cy, cz, playerChanged);
    }

    /** Separate class so Sable's types are only loaded once a plot-space section shows up. */
    private static final class Impl {
        static boolean route(Level level, int cx, int cy, int cz, boolean playerChanged) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return false;
            }
            LevelPlot plot = container.getPlot(cx, cz);
            if (plot == null || !(plot.getSubLevel() instanceof ClientSubLevel ship)) {
                return false;
            }
            SubLevelRenderData renderData = ship.getRenderData();
            if (renderData != null) {
                renderData.setDirty(cx, cy, cz, playerChanged);
            }
            if (!logged) {
                logged = true;
                LOGGER.info("[IPSableViewAreaDirty] routing ship block changes to Sable's render data (vanilla renderer; first: section {}, {}, {} in {})",
                    cx, cy, cz, level.dimension().location());
            }
            // like Sable's hook, the view area's own section for a plot chunk is never drawn: leave it alone
            return true;
        }
    }
}
