/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.miscellaneous;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PortalShape.class)
public interface IEPortalShape {
    /**
     * Vanilla's nether portal frame test (NeoForge: {@code isPortalFrame}); mods that add frame blocks either
     * override {@code isPortalFrame} or replace this field (Netherstorm), so it is read on every call.
     */
    @Accessor("FRAME")
    static BlockBehaviour.StatePredicate ip_getFrame() {
        throw new AssertionError();
    }
}
