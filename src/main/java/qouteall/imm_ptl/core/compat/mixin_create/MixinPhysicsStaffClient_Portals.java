/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Position;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortalsClient;

import java.util.UUID;

/**
 * The Create Aeronautics (Simulated) physics staff across portals, client side ({@link IPPhysicsStaffPortalsClient}):
 * picking ships behind portals and far halves, following the drag through portal crossings of the player and the ship,
 * and drawing the local player's beam and particles where the ship is seen (the beam also in the view through the portal
 * it goes through).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public class MixinPhysicsStaffClient_Portals {
    @WrapOperation(
        method = "onItemUsed",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"),
        require = 0
    )
    private HitResult ip_pickAcross(final LocalPlayer player, final double range, final float partialTick, final boolean fluids,
                                    final Operation<HitResult> original) {
        return IPPhysicsStaffPortalsClient.pick(player, original.call(player, range, partialTick, fluids));
    }

    @WrapOperation(
        method = "onItemUsed",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContainingClient(Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;"),
        require = 0
    )
    private ClientSubLevel ip_pickedShip(final ActiveSableCompanion helper, final Position position, final Operation<ClientSubLevel> original) {
        return IPPhysicsStaffPortalsClient.pickedShip(original.call(helper, position));
    }

    @Inject(method = "onItemUsed", at = @At("RETURN"), require = 0)
    private void ip_afterItemUsed(final CallbackInfo ci) {
        IPPhysicsStaffPortalsClient.afterItemUsed();
    }

    @Inject(method = "startDraggingSubLevel", at = @At("RETURN"), require = 0)
    private void ip_onDragStarted(final SubLevel ship, final net.minecraft.core.BlockPos pos, final LocalPlayer player,
                                  final InteractionHand hand, final CallbackInfo ci) {
        IPPhysicsStaffPortalsClient.onDragStarted(((PhysicsStaffClientHandler) (Object) this).getDragSession(), ship, player);
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void ip_tick(final CallbackInfo ci) {
        IPPhysicsStaffPortalsClient.tick((PhysicsStaffClientHandler) (Object) this);
    }

    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/SubLevel;isRemoved()Z"),
        require = 0
    )
    private boolean ip_isDragLost(final SubLevel ship, final Operation<Boolean> original) {
        return IPPhysicsStaffPortalsClient.isDragLost(ship, original.call(ship));
    }

    @WrapOperation(
        method = "lambda$onRender$3",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContainingClient(Lnet/minecraft/core/Position;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;"),
        require = 0
    )
    private static ClientSubLevel ip_beamShip(final ActiveSableCompanion helper, final Position position, final Operation<ClientSubLevel> original,
                                              @Local(argsOnly = true) final UUID owner) {
        return IPPhysicsStaffPortalsClient.beamShip(owner, original.call(helper, position));
    }

    @WrapOperation(
        method = "lambda$onRender$3",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 0
    )
    private static Player ip_beamPlayer(final ClientLevel level, final UUID owner, final Operation<Player> original) {
        return IPPhysicsStaffPortalsClient.beamPlayer(owner, original.call(level, owner));
    }

    @WrapOperation(
        method = "lambda$onRender$3",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffClientHandler$PhysicsBeam;render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/createmod/catnip/render/SuperRenderTypeBuffer;Lnet/minecraft/world/phys/Vec3;F)V"),
        require = 0
    )
    private static void ip_beamSegment(final PhysicsStaffClientHandler.PhysicsBeam beam, final Vec3 start, final Vec3 end, final PoseStack poseStack,
                                       final SuperRenderTypeBuffer buffer, final Vec3 camera, final float partialTick, final Operation<Void> original,
                                       @Local(argsOnly = true) final UUID owner) {
        final Vec3[] segment = IPPhysicsStaffPortalsClient.beamSegment(owner, start, end);
        if (segment != null) {
            original.call(beam, segment[0], segment[1], poseStack, buffer, camera, partialTick);
        }
    }

    @WrapOperation(
        method = "spawnParticles",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/companion/math/Pose3d;transformPosition(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
        require = 0
    )
    private static Vec3 ip_zapTarget(final Pose3d pose, final Vec3 point, final Operation<Vec3> original,
                                     @Local(argsOnly = true) final SubLevel ship) {
        return IPPhysicsStaffPortalsClient.toPlayerWorld(ship, original.call(pose, point));
    }

    @WrapOperation(
        method = "spawnParticles",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
        require = 0
    )
    private static void ip_particlePos(final Level level, final ParticleOptions particle, final double x, final double y, final double z,
                                       final double dx, final double dy, final double dz, final Operation<Void> original,
                                       @Local(argsOnly = true) final SubLevel ship) {
        final Vec3 pos = IPPhysicsStaffPortalsClient.particlePos(ship, level, new Vec3(x, y, z));
        original.call(level, particle, pos.x, pos.y, pos.z, dx, dy, dz);
    }
}
