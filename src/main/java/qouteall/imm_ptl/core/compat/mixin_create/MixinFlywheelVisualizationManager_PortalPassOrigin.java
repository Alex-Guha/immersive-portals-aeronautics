/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.backend.Engine;
import net.minecraft.client.Camera;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Flywheel's frame plan (1.0.6, javap: {@code VisualizationManagerImpl$LateInit}) is "if the engine's render origin
 * moved ({@code Engine.updateRenderOrigin(camera)}, more than 256 blocks from the camera) recreate every visual, else
 * run every visual's {@code beginFrame}". IP portal passes run that plan with the pass's camera, so a view whose camera
 * lands far away (a nested view back through scaled portals, a portal to a far place in the same dimension) moved the
 * origin there and the main pass moved it back: every frame such a view was on screen, all of the level's visuals were
 * rebuilt twice and none got {@code beginFrame}. Contraptions then kept the pose of their constructor; Create: Pocket
 * Sized scales a contraption's body only in {@code beginFrame} (its bogeys in {@code animate}, which the carriage's
 * constructor runs), so a train on a scaled ship drew its body at 1x on scaled wheels.
 * <p>
 * In a portal pass the origin stays where it is unless the camera is more than {@link #IP_FAR} blocks from it, where
 * float precision would start to show; the main view moves it as before. Wrapping the call in the plan covers every
 * backend (Flywheel's own, Colorwheel's).
 */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl$LateInit", remap = false)
public class MixinFlywheelVisualizationManager_PortalPassOrigin {
    @Unique
    private static final Logger IP_LOGGER = LogUtils.getLogger();
    @Unique
    private static final double IP_FAR = 8192.0;
    @Unique
    private static long ip_lastLog;
    @Unique
    private static int ip_heldSinceLog;

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/backend/Engine;updateRenderOrigin(Lnet/minecraft/client/Camera;)Z"),
        require = 0, expect = 0
    )
    private boolean ip_holdOriginInPortalPass(final Engine engine, final Camera camera, final Operation<Boolean> original) {
        if (PortalRendering.isRendering()) {
            final Vec3i origin = engine.renderOrigin();
            final Vec3 position = camera.getPosition();
            final double dx = position.x - origin.getX();
            final double dy = position.y - origin.getY();
            final double dz = position.z - origin.getZ();
            if (dx * dx + dy * dy + dz * dz <= IP_FAR * IP_FAR) {
                ip_noteHeld(origin, position);
                return false;
            }
        }
        return original.call(engine, camera);
    }

    /** Logs, at most every 10 s, that a portal pass would have moved the origin (and so rebuilt every visual). */
    @Unique
    private static void ip_noteHeld(final Vec3i origin, final Vec3 position) {
        final double distance = Math.sqrt(position.distanceToSqr(origin.getX(), origin.getY(), origin.getZ()));
        if (distance <= 256.0) {
            return;
        }
        ip_heldSinceLog++;
        final long now = System.currentTimeMillis();
        if (now - ip_lastLog < 10_000L) {
            return;
        }
        ip_lastLog = now;
        IP_LOGGER.info("[IPFlywheelOrigin] kept Flywheel's render origin {} for a portal pass whose camera is {} blocks away"
                + " (portal layer {}; {} such passes since the last line): its visuals are not rebuilt",
            origin.toShortString(), String.format("%.0f", distance), PortalRendering.getPortalLayer(), ip_heldSinceLog);
        ip_heldSinceLog = 0;
    }
}
