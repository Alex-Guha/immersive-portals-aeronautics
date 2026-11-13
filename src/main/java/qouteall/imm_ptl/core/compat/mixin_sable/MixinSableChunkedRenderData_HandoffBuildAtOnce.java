/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.Camera;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;

/**
 * Builds the sections of a ship's new copy just handed off within its own level at once, and uploads them, instead of
 * in the background ({@link IPStraddleHandoffLatch#buildAtOnce}): the old copy is held only until the new one is added,
 * so the new copy has to be drawable in that frame. At most {@value #IP_MAX_PER_COMPILE} sections per compile: a large
 * ship's remaining sections are built in the background as before, so that a frame does not stall on all of them.
 */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public class MixinSableChunkedRenderData_HandoffBuildAtOnce {
    @Unique
    private static final int IP_MAX_PER_COMPILE = 16;
    @Unique
    private int ip_builtThisCompile;

    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Shadow
    @Final
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Inject(method = "compileSections", at = @At("HEAD"), require = 0)
    private void ip_resetBuildBudget(final PrioritizeChunkUpdates chunkUpdates, final RenderRegionCache cache, final Camera camera,
                                     final CallbackInfo ci) {
        this.ip_builtThisCompile = 0;
    }

    @WrapOperation(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;rebuildSectionAsync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
        ),
        require = 0
    )
    private void ip_buildHandedOffCopyAtOnce(final SectionRenderDispatcher.RenderSection section, final SectionRenderDispatcher dispatcher,
                                             final RenderRegionCache cache, final Operation<Void> original) {
        if (this.sectionRenderDispatcher == null || this.ip_builtThisCompile >= IP_MAX_PER_COMPILE
                || !IPStraddleHandoffLatch.buildAtOnce(this.subLevel)) {
            original.call(section, dispatcher, cache);
            return;
        }
        this.ip_builtThisCompile++;
        this.sectionRenderDispatcher.rebuildSectionSync(section, cache);
        this.sectionRenderDispatcher.uploadAllPendingUploads();
    }
}
