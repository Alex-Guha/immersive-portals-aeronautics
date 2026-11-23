/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Sable's split detection launches pieces that are still attached when a batch of removed blocks (a portal's blocks at
 * a shatter, an explosion) takes its root along. Three faults, each fixed here:
 * <ul>
 * <li>Where two clearing floods meet, Sable points the absorbed flood's index at the other one, but indices pointed at
 * the absorbed one earlier keep their old value: the merge is not transitive, so one connected region can end up in
 * several groups. Every index with the absorbed value now takes the new one.</li>
 * <li>{@code split()} counts the positions it collected, some of which have turned to air since, against the block
 * count: a ship cleared whole is then taken for a real split and re-made as a new ship. Air positions are dropped
 * first.</li>
 * <li>When everything was cleared, {@code split()} keeps the largest group and rebuilds the block count from it, then
 * moving the other groups out subtracts their blocks a second time. The count is put back after each move.</li>
 * </ul>
 * {@code -Dip_aeronautics.stockSplits=true} leaves Sable's behaviour as it is (comparison runs).
 */
@Mixin(
    value = SubLevelHeatMapManager.class,
    remap = false
)
public abstract class MixinSableHeatMap_SplitFixes {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();
    @Unique
    private static final boolean ip_STOCK = Boolean.getBoolean("ip_aeronautics.stockSplits");

    @Shadow
    @Final
    private ServerSubLevel subLevel;
    @Shadow
    @Final
    private Long2IntOpenHashMap subLevelSplits;
    @Shadow
    private int solidCount;

    /** Whether the current {@code split()} rebuilt the heat map, and so the block count, before moving groups out. */
    @Unique
    private boolean ip_rebuiltInSplit;

    @WrapOperation(
        method = "step",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/IntArrayList;set(II)I"),
        require = 0
    )
    private int ip_mergeFloodsTransitively(final IntArrayList indexMap, final int index, final int value,
                                           final Operation<Integer> original) {
        if (ip_STOCK) {
            return original.call(indexMap, index, value);
        }
        final int absorbed = indexMap.getInt(index);
        for (int i = 0; i < indexMap.size(); i++) {
            if (indexMap.getInt(i) == absorbed) {
                indexMap.set(i, value);
            }
        }
        return absorbed;
    }

    @Inject(
        method = "split",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_dropAirBeforeSplit(final CallbackInfo ci) {
        this.ip_rebuiltInSplit = false;
        if (ip_STOCK) {
            return;
        }
        final Level level = this.subLevel.getLevel();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final LongIterator it = this.subLevelSplits.keySet().iterator(); it.hasNext(); ) {
            if (level.getBlockState(pos.set(it.nextLong())).isAir()) {
                it.remove();
            }
        }
    }

    @Inject(
        method = "rebuildHeatmapFrom",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_noteRebuild(final List<BlockPos> blocks, final CallbackInfo ci) {
        this.ip_rebuiltInSplit = true;
    }

    @WrapOperation(
        method = "split",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper;assembleBlocks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/lang/Iterable;Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;)Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"
        ),
        require = 0
    )
    private ServerSubLevel ip_keepCountAfterRebuild(final ServerLevel level, final BlockPos anchor,
                                                    final Iterable<BlockPos> blocks, final BoundingBox3ic bounds,
                                                    final Operation<ServerSubLevel> original) {
        final int before = this.solidCount;
        final ServerSubLevel piece = original.call(level, anchor, blocks, bounds);
        if (this.ip_rebuiltInSplit && !ip_STOCK) {
            this.solidCount = before;
        }
        int count = 0;
        for (final BlockPos ignored : blocks) {
            count++;
        }
        ip_LOGGER.info("[IPSplit] ship {} split: {} block(s) off as ship {} ({} left{})", this.subLevel.getUniqueId(),
                count, piece == null ? "none" : piece.getUniqueId(), this.solidCount,
                this.ip_rebuiltInSplit ? ", root was lost" : "");
        return piece;
    }
}
