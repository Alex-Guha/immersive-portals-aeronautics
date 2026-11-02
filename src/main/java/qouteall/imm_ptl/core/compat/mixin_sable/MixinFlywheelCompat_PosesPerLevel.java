/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_integration.IPSablePlotPoses;

/**
 * One table of ship render poses per level ({@link IPSablePlotPoses}). An entry missing from it (the entity joined
 * before its ship reached this client, or the table was just started) is made when a visual asks, instead of the
 * contraption being drawn at its plot coordinates.
 */
@Mixin(value = FlywheelCompatNeoForge.class, remap = false)
public class MixinFlywheelCompat_PosesPerLevel {
    private static final String TABLE =
        "Ldev/ryanhcode/sable/neoforge/compatibility/flywheel/FlywheelCompatNeoForge;RENDER_POSES:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;";

    @ModifyExpressionValue(method = {"preVisualizationFrame", "createRenderInfo"}, at = @At(value = "FIELD", target = TABLE))
    private static Long2ObjectMap<FlywheelCompatNeoForge.SubLevelFlwRenderState> ip_tableOfLevel(
        final Long2ObjectMap<FlywheelCompatNeoForge.SubLevelFlwRenderState> table, @Local(argsOnly = true) final Level level
    ) {
        return IPSablePlotPoses.tableOf(level);
    }

    @ModifyExpressionValue(method = "getInfo", at = @At(value = "FIELD", target = TABLE))
    private static Long2ObjectMap<FlywheelCompatNeoForge.SubLevelFlwRenderState> ip_tableOfVisual(
        final Long2ObjectMap<FlywheelCompatNeoForge.SubLevelFlwRenderState> table
    ) {
        final Level level = IPSablePlotPoses.visualLevel();
        return level == null ? table : IPSablePlotPoses.tableOf(level);
    }

    @Inject(method = "getInfo", at = @At("RETURN"), cancellable = true)
    private static void ip_makeMissingEntry(final long plotCoord, final CallbackInfoReturnable<FlywheelCompatNeoForge.SubLevelFlwRenderState> cir) {
        final Level level = IPSablePlotPoses.visualLevel();
        if (cir.getReturnValue() != null || level == null) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(ChunkPos.getX(plotCoord), ChunkPos.getZ(plotCoord));
        if (ship != null) {
            FlywheelCompatNeoForge.createRenderInfo(level, ship);
            cir.setReturnValue(IPSablePlotPoses.<FlywheelCompatNeoForge.SubLevelFlwRenderState>tableOf(level).get(plotCoord));
        }
    }
}
