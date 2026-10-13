/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.Carriage;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The per-dimension copies of a Create carriage ({@code Carriage.entities} is private). */
@Mixin(value = Carriage.class, remap = false)
public interface IPCarriageAccessor {
    @Accessor("entities")
    Map<ResourceKey<Level>, Carriage.DimensionalCarriageEntity> ip_getEntities();
}
