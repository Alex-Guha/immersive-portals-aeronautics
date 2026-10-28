/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.IPFoliageCompat;

/**
 * Interactive Foliage's GPU renderer draws and follows only the player's world ({@code IPFoliageCompat}).
 * <p>
 * Its draw in a portal pass of another dimension asked for that dimension's Iris pipeline, which recompiled its
 * programs and remeshed all its foliage, back and forth every frame. Its section tables are keyed by position alone, so
 * other worlds' chunk loads, unloads, light and block changes are not passed on (IP switches {@code Minecraft.level}
 * while it handles another world's packets and draws).
 */
@Mixin(targets = "net.karto.mc2.mc2_interactivefoliage.gpu.GpuFoliageRenderer", remap = false)
public class MixinInteractiveFoliageRenderer_PlayerWorldOnly {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldDraw(
        Vec3 camera, Frustum frustum, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        if (IPFoliageCompat.isOtherWorld()) {
            if (!ip_logged) {
                ip_logged = true;
                ip_LOGGER.info("[IPFoliage] Interactive Foliage is drawn only in the player's world; skipped in a " +
                    "portal view of {}", Minecraft.getInstance().level.dimension().location());
            }
            ci.cancel();
        }
    }

    @Inject(method = "discoverChunk", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldChunkLoad(ClientLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "forgetChunk", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldChunkUnload(ClientLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "onSectionAsked", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldSectionAsked(int x, int y, int z, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }

    @Inject(method = "onLightChanged", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldLight(int x, int y, int z, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }

    @Inject(method = "onBlockUpdated", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldBlockUpdate(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }

    @Inject(method = "onBlockChanged", at = @At("HEAD"), cancellable = true)
    private static void ip_skipOtherWorldBlockChange(BlockPos pos, CallbackInfo ci) {
        if (IPFoliageCompat.isOtherWorld()) {
            ci.cancel();
        }
    }
}
