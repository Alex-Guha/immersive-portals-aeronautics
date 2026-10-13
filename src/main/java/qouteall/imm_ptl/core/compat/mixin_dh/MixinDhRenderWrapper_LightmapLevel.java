/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH keeps one lightmap copy per dimension type and files each upload under the level that was
 * last <em>drawn</em> (the render state's level wrapper). Immersive Portals updates a light texture
 * for every pass, outer world and portal world alike, so with a portal on screen the two uploads
 * were filed under each other's dimension: the outer world's LODs were lit by the portal world's
 * lightmap and vice versa (overworld clouds seen from the nether were black). A light texture is
 * always computed for the level Minecraft currently holds, so file it under that level.
 */
@Mixin(value = MinecraftRenderWrapper_neoforge.class, remap = false)
public class MixinDhRenderWrapper_LightmapLevel {
    @Inject(
        method = "getLightmapClientLevelWrapper",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_fileLightmapUnderCurrentLevel(CallbackInfoReturnable<IClientLevelWrapper> cir) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            IClientLevelWrapper wrapper = ClientLevelWrapper_neoforge.getWrapper(level);
            if (wrapper != null) {
                cir.setReturnValue(wrapper);
            }
        }
    }
}
