/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSyncData;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainSides;

/**
 * The first sync data of a carriage copy that is not initialised on the client yet ({@code dce.pointsInitialised})
 * is written straight into the carriage's shared wheel points. The client's carriage runs a little behind the
 * server's (it approaches each snapshot over a few ticks), so when Create creates the other dimension's copy while a
 * carriage crosses a portal, that first sync snapped the whole carriage, the copy already on screen included, forward
 * to the server's position; the approach then settled it again (user: "a very brief jump ahead stutter" at the front
 * as the carriage enters). If another live copy of the carriage is already initialised, the new copy joins it: its
 * sync data only becomes its approach target, which only the copy driving the carriage uses
 * ({@code IPCreateClientCarriageDriver}).
 */
@Mixin(value = CarriageSyncData.class, remap = false)
public class MixinCarriageSyncData_JoinRunningCarriage {
    @Inject(method = "apply", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_joinRunningCarriage(final CarriageContraptionEntity entity, final Carriage carriage, final CallbackInfo ci) {
        if (IPCreateCarriageCrossing.STOCK || carriage == null || entity == null) {
            return;
        }
        final Carriage.DimensionalCarriageEntity own = IPCreateTrainSides.recordOf(carriage, entity);
        final Carriage.DimensionalCarriageEntity dce = own != null ? own : carriage.getDimensional(entity.level());
        if (dce.pointsInitialised) {
            return;
        }
        for (final Map.Entry<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> entry :
            ((IPCarriageAccessor) carriage).ip_getEntities().entrySet()) {
            if (entry.getValue() == dce || entry.getValue() == null) {
                continue;
            }
            final Carriage.DimensionalCarriageEntity other = entry.getValue();
            final CarriageContraptionEntity copy = other.entity.get();
            if (other.pointsInitialised && copy != null && copy != entity && !copy.isRemoved()) {
                dce.pointsInitialised = true;
                return;
            }
        }
    }
}
