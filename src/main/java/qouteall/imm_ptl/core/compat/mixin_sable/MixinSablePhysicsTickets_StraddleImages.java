/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplHostedTicketManagerMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;

import java.util.List;
import java.util.Map;

/**
 * Sable feeds a level's physics chart only the terrain around that level's own ships. A ship straddling a portal into
 * this level has an image collider here, so the terrain around the image must be fed too: the same tickets Sable's
 * per-ship loop takes (created with the section's upload, refreshed every tick so they do not expire), for every
 * loaded-enough chunk around each image (docs/lessons/straddle-sessions.md).
 */
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class MixinSablePhysicsTickets_StraddleImages {
    @Shadow
    @Final
    private Map<SectionPos, PhysicsChunkTicket> physicsChunks;

    @Inject(method = "update", at = @At("HEAD"), require = 0)
    private void ip_enrollStraddleImages(final ServerLevel level, final ServerSubLevelContainer container,
                                         final SubLevelPhysicsSystem system, final PhysicsPipeline pipeline,
                                         final double timeStep, final CallbackInfo ci) {
        final List<AABB> regions = IPStraddle.imageRegionsIn(level);
        if (regions.isEmpty()) {
            return;
        }
        final long gameTime = level.getGameTime();
        for (final AABB region : regions) {
            final int minX = Mth.floor(region.minX) >> 4, maxX = Mth.floor(region.maxX) >> 4;
            final int minY = Mth.floor(region.minY) >> 4, maxY = Mth.floor(region.maxY) >> 4;
            final int minZ = Mth.floor(region.minZ) >> 4, maxZ = Mth.floor(region.maxZ) >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        this.ip_enroll(level, pipeline, x, y, z, gameTime);
                    }
                }
            }
        }
    }

    @Unique
    private void ip_enroll(final ServerLevel level, final PhysicsPipeline pipeline, final int x, final int y, final int z,
                           final long gameTime) {
        final int index = level.getSectionIndexFromSectionY(y);
        if (index < 0 || index >= level.getSectionsCount()) {
            return;
        }
        final SectionPos sectionPos = SectionPos.of(x, y, z);
        PhysicsChunkTicket ticket = this.physicsChunks.get(sectionPos);
        if (ticket == null) {
            pipeline.handleChunkSectionAddition(level.getChunk(x, z).getSection(index), x, y, z, false);
            ticket = new PhysicsChunkTicket(sectionPos, gameTime, null);
            this.physicsChunks.put(sectionPos, ticket);
        }
        ticket.setLastInhabitedTick(gameTime);
    }
}
