/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets each dimension's level renderer update only the dynamic lights of its own dimension.
 * <p>
 * SodiumDynamicLights updates every light source (finds the sections a moved or changed light touches and marks them
 * for a rebuild) at the head of every {@code LevelRenderer.renderLevel}, at most once per 50 ms for all renderers
 * together, against whichever renderer happens to run first. IP also calls {@code renderLevel} for each portal view,
 * with another dimension's renderer. When the 50 ms ran out during a portal pass, the player's own lights were
 * updated against the portal-side renderer: their sections were rebuilt in the other dimension, and the light
 * remembered its new position and brightness as handled, so the player's world never got the rebuild. A light that
 * does not move again (a lamp on a parked ship, Create Dynamic Lights) then never lit the world around it.
 * <p>
 * Here a pass updates only the sources whose level is the one it draws ({@code Minecraft.level}, which IP switches for
 * a portal pass), with one 50 ms throttle per dimension. Lights of a dimension seen through a portal now light that
 * view too, which they did only by chance before.
 */
@Mixin(value = SodiumDynamicLights.class, remap = false)
public class MixinSodiumDynamicLights_PerDimensionUpdate {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Shadow
    private long lastUpdate;

    /** The last update of each dimension's lights; render thread only. */
    @Unique
    private final Map<ResourceKey<Level>, Long> ip_lastUpdateByDimension = new HashMap<>();

    @Unique
    private ResourceKey<Level> ip_passDimension;

    @Inject(method = "updateAll", at = @At("HEAD"))
    private void ip_beginPass(LevelRenderer renderer, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        ip_passDimension = level == null ? null : level.dimension();
        if (ip_passDimension != null) {
            lastUpdate = ip_lastUpdateByDimension.getOrDefault(ip_passDimension, 0L);
        }
    }

    @Inject(method = "updateAll", at = @At("RETURN"))
    private void ip_endPass(LevelRenderer renderer, CallbackInfo ci) {
        if (ip_passDimension != null) {
            ip_lastUpdateByDimension.put(ip_passDimension, lastUpdate);
        }
    }

    @WrapOperation(
        method = "updateAll",
        at = @At(
            value = "INVOKE",
            target = "Ltoni/sodiumdynamiclights/DynamicLightSource;sodiumdynamiclights$updateDynamicLight(Lnet/minecraft/client/renderer/LevelRenderer;)Z"
        )
    )
    private boolean ip_updateOwnDimensionOnly(
        DynamicLightSource source, LevelRenderer renderer, Operation<Boolean> original
    ) {
        Level passLevel = Minecraft.getInstance().level;
        if (passLevel != null && source.sdl$getDynamicLightLevel() != passLevel) {
            if (!ip_logged) {
                ip_logged = true;
                ip_LOGGER.info("[IPDynLights] dynamic lights are updated per dimension (first left to its own dimension: {} in {}, pass for {})",
                    source.getClass().getSimpleName(),
                    source.sdl$getDynamicLightLevel() == null ? null : source.sdl$getDynamicLightLevel().dimension().location(),
                    passLevel.dimension().location());
            }
            return false;
        }
        return original.call(source, renderer);
    }
}
