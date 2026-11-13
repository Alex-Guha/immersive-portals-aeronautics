/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

import java.util.List;

/**
 * Block effects of a straddling ship's far half on entities in the destination (docs/lessons/straddle-entities.md
 * leftovers). There the entity never "tracks" the ship (it belongs to another level), so vanilla reads the destination's
 * air where Sable would read the ship:
 * <ul>
 *     <li>speed and jump factors (soul sand, honey) from the far-half block at the feet, else the one below them, as
 *     vanilla's {@code getBlockSpeedFactor}/{@code getBlockJumpFactor};</li>
 *     <li>blocks the entity is inside (cobwebs, berry bushes, powder snow): their {@code entityInside} runs, as Sable's
 *     own pass over intersecting ships does, with the ship's level and plot position.</li>
 * </ul>
 */
@Mixin(Entity.class)
public abstract class MixinEntity_StraddleBlockEffects {
    @Shadow
    public abstract Level level();

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract boolean isAlive();

    @Shadow
    protected abstract void onInsideBlock(BlockState state);

    @ModifyReturnValue(method = "getBlockSpeedFactor", at = @At("RETURN"), require = 0)
    private float ip_farHalfSpeedFactor(final float factor) {
        if (factor != 1.0F) {
            return factor;
        }
        final Entity self = (Entity) (Object) this;
        final BlockState feet = IPStraddleCut.farHalfBlockUnder(self, 0.0);
        if (feet != null && feet.getBlock().getSpeedFactor() != 1.0F) {
            return feet.getBlock().getSpeedFactor();
        }
        final BlockState below = IPStraddleCut.farHalfBlockUnder(self, 0.500001);
        return below != null ? below.getBlock().getSpeedFactor() : factor;
    }

    @ModifyReturnValue(method = "getBlockJumpFactor", at = @At("RETURN"), require = 0)
    private float ip_farHalfJumpFactor(final float factor) {
        if (factor != 1.0F) {
            return factor;
        }
        final Entity self = (Entity) (Object) this;
        final BlockState feet = IPStraddleCut.farHalfBlockUnder(self, 0.0);
        if (feet != null && feet.getBlock().getJumpFactor() != 1.0F) {
            return feet.getBlock().getJumpFactor();
        }
        final BlockState below = IPStraddleCut.farHalfBlockUnder(self, 0.500001);
        return below != null ? below.getBlock().getJumpFactor() : factor;
    }

    @Inject(method = "checkInsideBlocks", at = @At("TAIL"), require = 0)
    private void ip_insideFarHalfBlocks(final CallbackInfo ci) {
        final List<IPStraddleCut.Projection> projections = IPStraddleCut.projectionsInto(this.level());
        if (projections.isEmpty()) {
            return;
        }
        final AABB bounds = this.getBoundingBox();
        for (final IPStraddleCut.Projection projection : projections) {
            if (!projection.mappedBounds().intersects(bounds)) {
                continue;
            }
            final Pose3d mapped = projection.map().mapPose(projection.ship().logicalPose());
            // the entity's box in the ship's plot space (bounds of its corners)
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                final Vec3 corner = mapped.transformPositionInverse(new Vec3(
                    (i & 1) == 0 ? bounds.minX : bounds.maxX, (i & 2) == 0 ? bounds.minY : bounds.maxY, (i & 4) == 0 ? bounds.minZ : bounds.maxZ));
                minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
            }
            final Level shipLevel = projection.ship().getLevel();
            final BlockPos min = BlockPos.containing(minX + 1.0E-7, minY + 1.0E-7, minZ + 1.0E-7);
            final BlockPos max = BlockPos.containing(maxX - 1.0E-7, maxY - 1.0E-7, maxZ - 1.0E-7);
            for (final BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (!this.isAlive()) {
                    return;
                }
                if (!projection.keepsBlock(mapped, pos)) {
                    continue;
                }
                final BlockState state = shipLevel.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                try {
                    state.entityInside(shipLevel, pos, (Entity) (Object) this);
                    this.onInsideBlock(state);
                } catch (final Throwable t) {
                    final CrashReport report = CrashReport.forThrowable(t, "Colliding entity with block");
                    final CrashReportCategory category = report.addCategory("Block being collided with");
                    CrashReportCategory.populateBlockDetails(category, shipLevel, pos, state);
                    throw new ReportedException(report);
                }
            }
        }
    }
}
