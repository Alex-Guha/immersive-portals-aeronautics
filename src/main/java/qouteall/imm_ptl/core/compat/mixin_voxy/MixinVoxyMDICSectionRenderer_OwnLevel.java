/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

/**
 * Neo Voxy's section renderer, made with each render system:
 * <ul>
 * <li>compiles the face shading of {@code Minecraft.level} into its shader (the player's world when IP makes another
 * dimension's render system): a Nether render system made from the Overworld shaded its LODs as the Overworld's. It now
 * takes the level the render system is made for ({@link IPVoxyPortalView#creationLevel}), as
 * {@code MixinVoxyRenderSystem_OwnHeightRange} does for the height range;</li>
 * <li>tells its shader to drop the track voxels its distant tracks stand in for, from one global flag: in a pass into
 * another world, where the distant tracks are not drawn ({@code MixinVoxyDistantExtras_PlayerWorldOnly}), that world's
 * own track LODs were dropped too.</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer", remap = false)
public class MixinVoxyMDICSectionRenderer_OwnLevel {
    @Redirect(
        method = "<init>",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"),
        require = 0
    )
    private ClientLevel ip_ownLevel(Minecraft minecraft) {
        ClientLevel level = IPVoxyPortalView.creationLevel();
        return level != null ? level : minecraft.level;
    }

    @WrapOperation(
        method = "uploadUniformBuffer",
        at = @At(value = "FIELD", target = "Lme/cortex/voxy/client/compat/LodPipelineHooks;distantTrackMeshesReady:Z"),
        require = 0
    )
    private boolean ip_distantTracksInPlayerWorldOnly(Operation<Boolean> original) {
        return original.call() && !IPVoxyPortalView.isOtherWorld();
    }
}
