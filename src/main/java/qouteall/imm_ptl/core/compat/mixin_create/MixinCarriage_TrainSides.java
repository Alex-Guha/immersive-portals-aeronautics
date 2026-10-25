/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;
import qouteall.imm_ptl.core.compat.create_integration.IPTrainSidesHolder;

/**
 * Create's per-dimension carriage code on side keys while a carriage spans a portal within one dimension
 * ({@link IPCreateTrainSides}): the wheel points' and bogeys' dimensions in the anchor, stress and copy-management code,
 * real levels for side keys, and real dimensions wherever the carriage's dimensions are listed. Create Teleporters
 * {@code @Redirect}s some of the same calls: these wrappers stack on its redirects.
 */
@Mixin(value = Carriage.class, remap = false)
public class MixinCarriage_TrainSides implements IPTrainSidesHolder {
    @Shadow
    private Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entities;

    @Unique
    private final IPCreateTrainSides.State ip_trainSides = new IPCreateTrainSides.State();

    @Override
    public IPCreateTrainSides.State ip_trainSides() {
        return ip_trainSides;
    }

    @Inject(method = "updateContraptionAnchors", at = @At("HEAD"))
    private void ip_prepareSides(final CallbackInfo ci) {
        IPCreateTrainSides.prepare((Carriage) (Object) this);
    }

    @Inject(method = "manageEntities", at = @At("HEAD"), cancellable = true)
    private void ip_keepSidesWithoutGraph(final Level level, final CallbackInfo ci) {
        if (!IPCreateTrainSides.canManageEntities((Carriage) (Object) this)) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = {"updateContraptionAnchors", "getAnchorDiff", "lambda$manageEntities$11"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/graph/TrackNode;getLocation()Lcom/simibubi/create/content/trains/graph/TrackNodeLocation;"
        )
    )
    private TrackNodeLocation ip_pointSide(final TrackNode node, final Operation<TrackNodeLocation> original) {
        return IPCreateTrainSides.located((Carriage) (Object) this, node, original.call(node));
    }

    @WrapOperation(
        method = "updateContraptionAnchors",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/CarriageBogey;getDimension()Lnet/minecraft/resources/ResourceKey;"
        )
    )
    private ResourceKey<Level> ip_bogeySide(final CarriageBogey bogey, final Operation<ResourceKey<Level>> original) {
        return IPCreateTrainSides.bogeySide(bogey, original.call(bogey));
    }

    @WrapOperation(
        method = "manageEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private ServerLevel ip_realLevel(final MinecraftServer server, final ResourceKey<Level> key, final Operation<ServerLevel> original) {
        return original.call(server, IPCreateTrainSides.realDimension(key));
    }

    @Inject(
        method = "getDimensional(Lnet/minecraft/resources/ResourceKey;)Lcom/simibubi/create/content/trains/entity/Carriage$DimensionalCarriageEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ip_sideRecord(final ResourceKey<Level> dimension, final CallbackInfoReturnable<Carriage.DimensionalCarriageEntity> cir) {
        final Carriage.DimensionalCarriageEntity side = IPCreateTrainSides.sideRecordFor((Carriage) (Object) this, dimension);
        if (side != null) {
            cir.setReturnValue(side);
        }
    }

    @Inject(method = "getPresentDimensions", at = @At("HEAD"), cancellable = true)
    private void ip_realDimensions(final CallbackInfoReturnable<List<ResourceKey<Level>>> cir) {
        if (entities.keySet().stream().anyMatch(IPCreateTrainSides::isSide)) {
            cir.setReturnValue(entities.keySet().stream().map(IPCreateTrainSides::realDimension).distinct().toList());
        }
    }

    @Inject(method = "getPositionInDimension", at = @At("HEAD"), cancellable = true)
    private void ip_positionOnASide(final ResourceKey<Level> dimension, final CallbackInfoReturnable<Optional<BlockPos>> cir) {
        if (entities.containsKey(dimension)) {
            return;
        }
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry : entities.entrySet()) {
            if (IPCreateTrainSides.isSide(entry.getKey()) && IPCreateTrainSides.realDimension(entry.getKey()).equals(dimension)
                && entry.getValue() != null && entry.getValue().positionAnchor != null) {
                cir.setReturnValue(Optional.of(BlockPos.containing(entry.getValue().positionAnchor)));
                return;
            }
        }
    }
}
