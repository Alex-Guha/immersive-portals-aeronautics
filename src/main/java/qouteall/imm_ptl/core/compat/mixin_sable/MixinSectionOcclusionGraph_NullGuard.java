/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards vanilla {@code SectionOcclusionGraph.onSectionCompiled} against a null
 * {@code currentGraph} (the graph is being rebuilt, e.g. after IP switches worlds while
 * Sable sub-level sections finish compiling): defers the section (cap 8192) and cancels,
 * then replays all deferred sections on the next call once the graph exists.
 */
@Mixin(SectionOcclusionGraph.class)
public class MixinSectionOcclusionGraph_NullGuard {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    @Unique
    private ArrayDeque<RenderSection> ip_deferredSections;
    @Unique
    private boolean ip_replaying;

    @Inject(
        method = "onSectionCompiled",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_deferWhenGraphRebuilding(RenderSection section, CallbackInfo ci) {
        try {
            boolean graphMissing = this.currentGraph == null || this.currentGraph.get() == null;
            if (graphMissing) {
                if (this.ip_deferredSections == null) {
                    this.ip_deferredSections = new ArrayDeque<>();
                }

                if (this.ip_deferredSections.isEmpty()) {
                    ip_LOGGER.debug("[IPSableOcclusionGuard] graph rebuilding; deferring section registrations");
                }

                if (this.ip_deferredSections.size() < 8192) {
                    this.ip_deferredSections.add(section);
                }

                ci.cancel();
                return;
            }

            if (this.ip_replaying) {
                return;
            }

            if (this.ip_deferredSections != null && !this.ip_deferredSections.isEmpty()) {
                this.ip_replaying = true;

                try {
                    int deferredCount = this.ip_deferredSections.size();
                    SectionOcclusionGraph self = (SectionOcclusionGraph) (Object) this;

                    RenderSection deferred;
                    while ((deferred = this.ip_deferredSections.poll()) != null) {
                        self.onSectionCompiled(deferred);
                    }

                    ip_LOGGER.debug("[IPSableOcclusionGuard] replayed {} deferred section registrations", deferredCount);
                } finally {
                    this.ip_replaying = false;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
