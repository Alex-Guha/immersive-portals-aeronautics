/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An elevator contraption's column, relative to its anchor ({@code ElevatorContraption.column} is protected). */
@Mixin(value = ElevatorContraption.class, remap = false)
public interface IPElevatorContraptionAccessor {
    @Accessor("column")
    ElevatorColumn.ColumnCoords ip_getColumn();

    @Accessor("column")
    void ip_setColumn(ElevatorColumn.ColumnCoords column);
}
