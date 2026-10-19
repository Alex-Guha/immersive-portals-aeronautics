/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserBlockEntity;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Draws a laser beam the way its renderer does, for the legs of a beam past a portal ({@code IPLaserPortalsClient}). */
@Mixin(value = AbstractLaserRenderer.class, remap = false)
public interface IPLaserRendererInvoker {
    @Invoker(value = "transformPose", remap = false)
    void ip$transformPose(AbstractLaserBlockEntity blockEntity, LaserBehaviour laser, PoseStack pose);

    @Invoker(value = "createLaser", remap = false)
    void ip$createLaser(Vector4f color, PoseStack pose, MultiBufferSource buffer, float maxLength, float length);
}
