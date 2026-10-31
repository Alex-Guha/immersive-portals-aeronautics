/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;

public enum PerformanceLevel {
    good, medium, bad;
    
    public static PerformanceLevel getClientPerformanceLevel(
        int averageFPS,
        int averageFreeMemoryMB
    ) {
        if (averageFPS > 50 && averageFreeMemoryMB > 800) {
            return good;
        }
        // Immersive Aeronautics: bad shrinks what portals load to almost nothing, which raises the fps, which restores
        // it: at the 30 fps it took before, normal for a heavy shader pack, chunks kept vanishing from portal views
        else if (averageFPS > 15 && averageFreeMemoryMB > 300) {
            return medium;
        }
        else {
            return bad;
        }
    }
    
    
    public static PerformanceLevel getServerPerformanceLevel(MinecraftServer server) {
        ServerTickRateManager tickRateManager = server.tickRateManager();
        long averageTickTimeNanos = server.getAverageTickTimeNanos();
        
        long nanosecondsPerTick = tickRateManager.nanosecondsPerTick();
        
        if (averageTickTimeNanos < nanosecondsPerTick * 0.8) {
            return good;
        }
        else if (averageTickTimeNanos < nanosecondsPerTick) {
            return medium;
        }
        else {
            return bad;
        }
    }
    
    public static int getVisiblePortalRangeChunks(PerformanceLevel level) {
        if (level == good) {
            return 8;
        }
        else if (level == medium) {
            return 3;
        }
        else {
            return 1;
        }
    }
    
    public static int getIndirectVisiblePortalRangeChunks(PerformanceLevel level) {
        if (level == good) {
            return 2;
        }
        else if (level == medium) {
            return 1;
        }
        else {
            return 0;
        }
    }
    
    public static int getIndirectLoadingRadiusCap(
        PerformanceLevel level
    ) {
        if (level == good) {
            return 32;
        }
        else if (level == medium) {
            return 7;
        }
        else {
            return 2;
        }
    }
    
    public static int getPortalRenderingDistance(
        PerformanceLevel level, int originalDistance
    ) {
        if (level == good) {
            return originalDistance;
        }
        else if (level == medium) {
            return Math.max(2, originalDistance / 2);
        }
        else {
            return 2;
        }
    }
}
