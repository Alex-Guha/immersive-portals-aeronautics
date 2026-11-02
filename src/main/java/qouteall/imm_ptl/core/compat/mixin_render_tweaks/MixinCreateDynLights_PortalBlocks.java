/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * A portal on a ship glows with a few of its blocks. Create Dynamic Lights gives every light-emitting block of a ship
 * a dynamic light, so a 19x24 portal was 456 sources of level 15 moving with the ship, each one looked at by every
 * light lookup. SodiumDynamicLights takes the brightest source and does not add them up, so one source per 4 blocks
 * looks almost the same: 30 to 42 sources for that portal, by how it sits on the grid.
 * <p>
 * A block keeps its source when, along each axis in the portal's plane, it is on a line of the block grid every 4
 * blocks or first in its row (so a small or thin portal keeps some). That needs no other block's result, which keeps
 * the choice the same at every scan, whatever the order. Chunk sections are a multiple of 4 wide, so the grid can be
 * read from section coordinates, and a block off a line has its neighbour in the same section.
 */
@Pseudo
@Mixin(targets = "top.leonx.dynlight.sodium.sable.SableSubLevelEventHandler", remap = false)
public class MixinCreateDynLights_PortalBlocks {
    @Unique
    private static final Logger IP_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged;

    @ModifyExpressionValue(
        method = "scanChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getLightEmission()I"
        )
    )
    private static int ip_portalBlocksOnAGrid(
        int emission, @Local(name = "state") BlockState state, @Local(name = "section") LevelChunkSection section,
        @Local(name = "x") int x, @Local(name = "y") int y, @Local(name = "z") int z
    ) {
        if (emission == 0 || state.getBlock() != PortalPlaceholderBlock.instance
            || ip_keepsSource(section, state, x, y, z)) {
            return emission;
        }
        if (!ip_logged) {
            ip_logged = true;
            IP_LOGGER.info("[IPDynLights] a portal on a ship keeps a dynamic light on every 4th block");
        }
        return 0;
    }

    @Unique
    private static boolean ip_keepsSource(LevelChunkSection section, BlockState state, int x, int y, int z) {
        Direction.Axis normal = state.getValue(PortalPlaceholderBlock.AXIS);
        return (normal == Direction.Axis.X || (x & 3) == 0 || !ip_isPortalBlock(section, x - 1, y, z))
            && (normal == Direction.Axis.Y || (y & 3) == 0 || !ip_isPortalBlock(section, x, y - 1, z))
            && (normal == Direction.Axis.Z || (z & 3) == 0 || !ip_isPortalBlock(section, x, y, z - 1));
    }

    @Unique
    private static boolean ip_isPortalBlock(LevelChunkSection section, int x, int y, int z) {
        return section.getBlockState(x, y, z).getBlock() == PortalPlaceholderBlock.instance;
    }
}
