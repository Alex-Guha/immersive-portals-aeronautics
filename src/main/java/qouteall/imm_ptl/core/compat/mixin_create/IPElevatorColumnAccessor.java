/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import java.util.List;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An elevator column's level, coordinates and contact heights (protected in Create). */
@Mixin(value = ElevatorColumn.class, remap = false)
public interface IPElevatorColumnAccessor {
    @Accessor("level")
    LevelAccessor ip_getLevel();

    @Accessor("coords")
    ElevatorColumn.ColumnCoords ip_getCoords();

    @Accessor("contacts")
    List<Integer> ip_getContacts();
}
