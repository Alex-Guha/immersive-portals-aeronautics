/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;
import dev.simulated_team.simulated.content.blocks.spring.SpringRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSprings;

/**
 * Springs at a portal ({@link IPStraddleSprings}). Aeronautics' renderer places the other end of the spring relative to
 * this one, so both are taken with their real poses also while this end's ship is drawn mapped through the portal (the
 * block entity transform then applies the mapped pose). It looks the other ship up in {@code Minecraft.level}, which
 * there is the destination: without its own level it finds nothing and draws to the other end's plot coordinates.
 */
@Mixin(value = SpringRenderer.class, remap = false)
public abstract class MixinSpringRenderer_StraddleCut {
    @Redirect(method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getContainer(Lnet/minecraft/client/multiplayer/ClientLevel;)Ldev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer;"),
            require = 0)
    private ClientSubLevelContainer ip_ownLevel(final ClientLevel rendering, @Local(argsOnly = true) final SpringBlockEntity spring) {
        return SubLevelContainer.getContainer(spring.getLevel() instanceof final ClientLevel own ? own : rendering);
    }

    @Redirect(method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"),
            require = 0)
    private Pose3dc ip_realPose(final ClientSubLevel ship) {
        return IPStraddleRender.realRenderPose(ship);
    }
}
