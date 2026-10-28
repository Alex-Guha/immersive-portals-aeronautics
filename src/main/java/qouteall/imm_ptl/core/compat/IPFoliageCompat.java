/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumLevelSlice;
import qouteall.imm_ptl.core.compat.mixin_render_tweaks.IEInteractiveFoliageRenderer;
import qouteall.imm_ptl.core.mixin.client.accessor.IERenderChunkRegion;

/**
 * Keeps Interactive Foliage (mc2_interactivefoliage) and Sway to the world the player is in.
 * <p>
 * Both keep one world's state in static fields, keyed by block or section position alone, and take the world from
 * {@code Minecraft.level}, which IP switches for each portal pass and while it handles another world's packets.
 * Interactive Foliage's GPU renderer (drawn at {@code AFTER_CUTOUT_BLOCKS}) also builds its four Iris programs for one
 * Iris pipeline and, whenever the current pipeline is another ({@code IrisFoliageShaders.ready}), recompiles them and
 * remeshes every foliage section; Iris keeps one pipeline per dimension, so with a shader pack and a portal to another
 * dimension in view that ran several times a frame (6-8 fps, 2026-10-28). Its chunk meshes left foliage out by
 * position in every world, and other worlds' chunk loads, unloads and block changes went into the player's world's
 * tables. Sway recomputed its bends around the player in the portal's world and marked those sections for rebuilds
 * every frame ({@code MixinSwayEngine_OncePerFrame}).
 * <p>
 * So Interactive Foliage draws only in passes of the player's world, other worlds' chunk meshes keep their foliage
 * (portal views show it without waving), and other worlds' events are not passed on
 * ({@code MixinInteractiveFoliage*_PlayerWorldOnly}). On a crossing its renderer is switched off inside the world left
 * behind, which gives that world's meshes their foliage back, and it starts again in the new world.
 */
public class IPFoliageCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean FOLIAGE_LOADED = ModList.get().isLoaded("mc2_interactivefoliage");
    private static final boolean SODIUM_LOADED = ModList.get().isLoaded("sodium");

    private static boolean loggedHandOverFailure = false;

    /**
     * Whether the code running now draws or handles a world the player is not in: a portal pass of another dimension,
     * or another world's packets. Render thread only.
     */
    public static boolean isOtherWorld() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.level != null && client.level != client.player.level();
    }

    public static boolean isOtherWorld(@Nullable Level level) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && level != null && level != player.level();
    }

    /** Whether a chunk mesh being built (vanilla's or Sodium's) belongs to a world the player is not in. */
    public static boolean isOtherWorldBuild(BlockAndTintGetter getter) {
        Level level = null;
        if (getter instanceof IERenderChunkRegion region) {
            level = region.ip_getLevel();
        }
        else if (SODIUM_LOADED && getter instanceof IESodiumLevelSlice slice) {
            level = slice.ip_getLevel();
        }
        return isOtherWorld(level);
    }

    /** Called after the player crossed into another dimension, with the new world already set up. */
    public static void onPlayerChangeDimensionClient(ResourceKey<Level> from) {
        if (!FOLIAGE_LOADED) {
            return;
        }
        ClientLevel fromWorld = ClientWorldLoader.getOptionalWorld(from);
        if (fromWorld == null) {
            return;
        }
        try {
            // deactivate remeshes the sections it took foliage out of in Minecraft.level's renderer, and drops its
            // GPU meshes; its next draw in the new world reseeds from there
            ClientWorldLoader.withSwitchedWorld(
                fromWorld, () -> IEInteractiveFoliageRenderer.ip_deactivate(Minecraft.getInstance())
            );
            LOGGER.info("[IPFoliage] Interactive Foliage handed over from {} to the player's new world",
                from.location());
        }
        catch (RuntimeException | LinkageError e) {
            if (!loggedHandOverFailure) {
                loggedHandOverFailure = true;
                LOGGER.warn("[IPFoliage] could not switch Interactive Foliage off in {}; its foliage there may be " +
                    "missing in portal views until those chunks are rebuilt", from.location(), e);
            }
        }
    }
}
