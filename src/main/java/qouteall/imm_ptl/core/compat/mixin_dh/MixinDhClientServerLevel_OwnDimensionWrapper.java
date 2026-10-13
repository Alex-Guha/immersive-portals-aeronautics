/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * In singleplayer a DH level answers "which client level am I?" with the player's current level
 * (with IP, the player's actual level). For a dimension only seen through a portal that is the
 * wrong level: the overworld's cloud handler asked the nether for its cloud colour, and the nether's
 * fixed midnight gave black clouds by day. The answer is now the client level of the DH level's own
 * dimension whenever IP holds one; the player's own dimension keeps DH's answer.
 */
@Mixin(value = DhClientServerLevel.class, remap = false)
public class MixinDhClientServerLevel_OwnDimensionWrapper {
    @Inject(
        method = "getClientLevelWrapper",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_wrapperOfOwnDimension(CallbackInfoReturnable<IClientLevelWrapper> cir) {
        if (!IPDhPortalView.ENABLED) {
            return;
        }
        try {
            if (!(((AbstractDhServerLevel) (Object) this).getServerLevelWrapper() instanceof ServerLevelWrapper_neoforge serverWrapper)) {
                return;
            }
            ServerLevel serverLevel = serverWrapper.getWrappedMcObject();
            if (serverLevel == null) {
                return;
            }
            ResourceKey<Level> dim = serverLevel.dimension();
            if (dim.equals(RenderStates.originalPlayerDimension)) {
                return;
            }
            ClientLevel clientLevel = ClientWorldLoader.getOptionalWorld(dim);
            if (clientLevel == null) {
                return;
            }
            IClientLevelWrapper wrapper = ClientLevelWrapper_neoforge.getWrapper(clientLevel);
            if (wrapper != null) {
                cir.setReturnValue(wrapper);
            }
        } catch (Throwable ignored) {
        }
    }
}
