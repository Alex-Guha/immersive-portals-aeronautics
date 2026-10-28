/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Create's drawing of one belt item; items past a portal's plane are drawn with it in the world behind the portal. */
@Mixin(value = BeltRenderer.class, remap = false)
public interface IPBeltRendererInvoker {
    @Invoker("renderItem")
    void ip$renderItem(
        BeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay,
        Direction beltFacing, Vec3i directionVec, BeltSlope slope, int verticality, boolean slopeAlongX, boolean onContraption,
        TransportedItemStack transported, Vec3 beltStartOffset
    );
}
