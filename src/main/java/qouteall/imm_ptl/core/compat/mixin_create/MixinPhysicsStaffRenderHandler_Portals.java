/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortalsClient;

import java.util.UUID;

/**
 * Create Aeronautics' (Simulated) physics staff overlays across portals ({@link IPPhysicsStaffPortalsClient}). Simulated
 * draws them in every pass (IP's portal views included) from the player's world:
 * <ul>
 *     <li>hover box: picked as the staff picks (ships behind portals too), drawn only in passes rendering the ship's
 *     world (it is a plot position, which repeats between worlds); the dragged block likewise;</li>
 *     <li>lock icons: those of the world being rendered, not the player's, and not those a portal is in front of (they
 *     are drawn without depth test).</li>
 * </ul>
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffRenderHandler", remap = false)
public class MixinPhysicsStaffRenderHandler_Portals {
    @Shadow
    private static BlockPos hoverBlockPos;

    @WrapOperation(
        method = "updateHoverPos",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"),
        require = 0
    )
    private static HitResult ip_hoverPick(final LocalPlayer player, final double range, final float partialTick, final boolean fluids,
                                          final Operation<HitResult> original) {
        return IPPhysicsStaffPortalsClient.hoverPick(player, partialTick, () -> original.call(player, range, partialTick, fluids));
    }

    @WrapOperation(
        method = "updateHoverPos",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContaining(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
        require = 0
    )
    private static SubLevel ip_hoverShip(final ActiveSableCompanion helper, final Level level, final Position position,
                                         final Operation<SubLevel> original) {
        return IPPhysicsStaffPortalsClient.hoverShip(level, original.call(helper, level, position));
    }

    @Inject(method = "updateHoverPos", at = @At("RETURN"), require = 0)
    private static void ip_hideAcross(final Minecraft minecraft, final LocalPlayer player, final CallbackInfo ci) {
        if (IPPhysicsStaffPortalsClient.hidesHoverBox(minecraft.level)) {
            hoverBlockPos = null;
        }
    }

    @ModifyArg(
        method = "renderSelectionBox",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffRenderHandler;renderAllLocks(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lfoundry/veil/api/client/render/MatrixStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;)V"),
        index = 2,
        require = 0
    )
    private static Level ip_renderedLevel(final Level playerLevel) {
        final Level rendered = Minecraft.getInstance().level;
        return rendered != null ? rendered : playerLevel;
    }

    @WrapOperation(
        method = "renderAllLocks",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getSubLevel(Ljava/util/UUID;)Ldev/ryanhcode/sable/sublevel/SubLevel;"),
        require = 0
    )
    private static SubLevel ip_lockShown(final SubLevelContainer container, final UUID id, final Operation<SubLevel> original) {
        final SubLevel ship = original.call(container, id);
        return ship != null && IPPhysicsStaffPortalsClient.hidesLockIcon(ship) ? null : ship;
    }
}
