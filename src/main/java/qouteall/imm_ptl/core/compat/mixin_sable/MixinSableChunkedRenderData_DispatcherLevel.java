/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.mixin.client.render.IESectionRenderDispatcher;

/**
 * Compiles a ship's sections from the ship's own level, and gives the dispatcher its level back afterwards.
 * <p>
 * Sable's reach-around render dispatcher (the Sodium path) is a singleton holding one vanilla
 * {@link SectionRenderDispatcher}, whose {@code level} is set only when a ship's render data is created.
 * IP keeps several client levels alive and their plot chunks share coordinates (plot (0,0) exists in
 * every dimension), so once ships of two dimensions exist on the client, sections of a ship were built
 * from whichever level a render data was created for last: either empty (no chunks there, sections never
 * complete, parts of ships invisible after a crossing) or filled with the other dimension's ship at the
 * same plot ("illusions" of other sub-levels attached). Setting the level right before each compile
 * makes the region snapshot come from the ship's own level.
 * <p>
 * On the vanilla renderer (no Sodium) Sable instead hands a ship {@code Minecraft.levelRenderer}'s own
 * terrain dispatcher ({@code VanillaSubLevelRenderDispatcher.createRenderData}), which is the player's world's
 * when a ship of a portal-side world gets its render data in a container tick. Left pointed at the ship's
 * level, that renderer then built every changed or newly loaded terrain section of the player's world from
 * the other world at the same coordinates (player report, 2026-11-04, 2.8.15: the overworld drawn in the
 * nether, chunks vanishing where blocks were placed, until a respawn reset the renderer). The level is put
 * back when the compile returns: {@code RenderSection.createCompileTask} snapshots the region from
 * {@code level} on the render thread, so tasks already scheduled keep the ship's level.
 * <p>
 * The dispatcher's {@code fixedBuffers} (used for nearby sections compiled synchronously) are the
 * {@code fixedBufferPack} of whatever {@link RenderBuffers} were current when the dispatcher was created.
 * The main buffer source writes into that same pack, and IP lends secondary sets to portal passes, so
 * like IP's own dispatchers ({@code MyGameRenderer}) the pack is switched to the current pass's set before
 * each compile.
 */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public class MixinSableChunkedRenderData_DispatcherLevel {
    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Shadow
    @Final
    private SectionRenderDispatcher sectionRenderDispatcher;

    @WrapMethod(method = "compileSections", require = 0)
    private void ip_compileFromOwnLevel(
        PrioritizeChunkUpdates chunkUpdates, RenderRegionCache renderRegionCache, Camera camera, Operation<Void> original
    ) {
        SectionRenderDispatcher dispatcher = this.sectionRenderDispatcher;
        ClientLevel level = this.subLevel.getLevel();
        ClientLevel previousLevel = null;
        boolean switched = false;

        if (level != null && dispatcher instanceof IESectionRenderDispatcher accessor) {
            previousLevel = accessor.ip_getLevel();
            if (previousLevel != level) {
                dispatcher.setLevel(level);
                switched = true;
            }

            RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
            if (renderBuffers != null) {
                SectionBufferBuilderPack pack = renderBuffers.fixedBufferPack();
                if (accessor.ip_getFixedBuffers() != pack) {
                    accessor.ip_setFixedBuffers(pack);
                }
            }
        }

        try {
            original.call(chunkUpdates, renderRegionCache, camera);
        } finally {
            if (switched && previousLevel != null) {
                dispatcher.setLevel(previousLevel);
            }
        }
    }
}
