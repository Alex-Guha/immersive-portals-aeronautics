/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.mixin.client.render.IELightTexture;
import qouteall.q_misc_util.Helper;

public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();
    public final Level world;
    
    public final LightTexture lightmapTexture;
    
    public DimensionRenderHelper(Level world) {
        this.world = world;
        
        if (client.level == world) {
            IEGameRenderer gameRenderer = (IEGameRenderer) client.gameRenderer;
            
            lightmapTexture = client.gameRenderer.lightTexture();
        }
        else {
            lightmapTexture = new LightTexture(client.gameRenderer, client);
            // Immersive Aeronautics: a new lightmap is all white (full bright) until its first update; compute it on
            // its first frame instead of after its first tick
            ((IELightTexture) lightmapTexture).ip_setUpdateLightTexture(true);
            Helper.log("Created lightmap texture for " + world.dimension().location());
        }
    }
    
    public void tick() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            lightmapTexture.tick();
        }
        // Immersive Aeronautics: every lightmap IP holds, the player's own included, updates every tick as vanilla's
        // tick() asks, even when another mod cancelled it. BadOptimizations' lightmap caching lets one lightmap per
        // game tick through: the first one ticked after the player's tickCount changes (one shared "tick changed"
        // flag). That is always one of these, ticked here after ClientLevel.tickEntities(), never the player's own
        // (GameRenderer.tick() comes before tickEntities()), so the player's lightmap froze (full bright at night,
        // dark by day) until a crossing swapped in another, and one that was never let through stayed all white.
        ((IELightTexture) lightmapTexture).ip_setUpdateLightTexture(true);
    }
    
    public void cleanUp() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            lightmapTexture.close();
        }
    }
    
}
