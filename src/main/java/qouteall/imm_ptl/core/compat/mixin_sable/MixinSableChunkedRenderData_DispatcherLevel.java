/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.mixin.client.render.IESectionRenderDispatcher;

/**
 * Compiles a ship's sections from the ship's own level.
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

    @Inject(
        method = "compileSections",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_compileFromOwnLevel(PrioritizeChunkUpdates chunkUpdates, RenderRegionCache renderRegionCache, Camera camera, CallbackInfo ci) {
        ClientLevel level = this.subLevel.getLevel();
        if (level != null && this.sectionRenderDispatcher != null) {
            this.sectionRenderDispatcher.setLevel(level);
        }
        RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
        if (this.sectionRenderDispatcher instanceof IESectionRenderDispatcher dispatcher && renderBuffers != null) {
            SectionBufferBuilderPack pack = renderBuffers.fixedBufferPack();
            if (dispatcher.ip_getFixedBuffers() != pack) {
                dispatcher.ip_setFixedBuffers(pack);
            }
        }
    }
}
