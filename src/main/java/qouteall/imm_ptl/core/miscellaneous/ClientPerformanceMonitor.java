/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.miscellaneous;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.commands.PortalDebugCommands;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

import java.util.ArrayDeque;

//@OnlyIn(Dist.CLIENT)
public class ClientPerformanceMonitor {
    
    public static PerformanceLevel level = PerformanceLevel.medium;
    
    public static class Record {
        public final int FPS;
        public final int freeMemoryMB;
        
        public Record(int FPS, int freeMemoryMB) {
            this.FPS = FPS;
            this.freeMemoryMB = freeMemoryMB;
        }
    }
    
    private static final ArrayDeque<Record> records = new ArrayDeque<>();
    private static int averageFps = 60;
    private static int minimumFps = 60;
    private static int averageFreeMemoryMB = 1000;
    private static final int sampleNum = 20;
    
    private static int counter = 0;
    
    public static void updateEverySecond(int newFps) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        
        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        long totalMemoryBytes = Runtime.getRuntime().totalMemory();
        long freeMemoryBytes = Runtime.getRuntime().freeMemory();
        long usedMemoryBytes = totalMemoryBytes - freeMemoryBytes;
        long actualFreeMemoryBytes = maxMemoryBytes - usedMemoryBytes;
        
        int freeMemoryMB = (int) PortalDebugCommands.toMiB(actualFreeMemoryBytes);
        
        records.addLast(new Record(newFps, freeMemoryMB));
        
        if (records.size() > sampleNum) {
            records.removeFirst();
        }
        
        averageFps = (int) records.stream().mapToInt(r -> r.FPS).average().orElse(60);
        minimumFps = records.stream().mapToInt(r -> r.FPS).min().orElse(60);
        averageFreeMemoryMB = (int) records.stream()
            .mapToInt(r -> r.freeMemoryMB).average().orElse(1000);
        
        counter++;
        if (counter % 5 == 0) {
            updateAndSend();
        }
    }
    
    public static int getAverageFps() {
        return averageFps;
    }
    
    public static int getMinimumFps() {
        return minimumFps;
    }
    
    /**
     * Immersive Aeronautics: the highest frame rate of the last {@code seconds} whole seconds, or 60 while fewer were
     * recorded (IPViewGuard).
     */
    public static int getHighestFpsOfLast(int seconds) {
        if (records.size() < seconds) {
            return 60;
        }
        int highest = 0;
        java.util.Iterator<Record> newestFirst = records.descendingIterator();
        for (int i = 0; i < seconds; i++) {
            highest = Math.max(highest, newestFirst.next().FPS);
        }
        return highest;
    }
    
    public static int getAverageFreeMemoryMB() {
        return averageFreeMemoryMB;
    }
    
    private static void updateAndSend() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        
        // Immersive Aeronautics: by free memory only (PerformanceLevel), no longer an option
        level = PerformanceLevel.getClientPerformanceLevel(averageFreeMemoryMB);
        
        McRemoteProcedureCallClient.tellServerToInvoke(
            "qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking.RemoteCallables.acceptClientPerformanceInfo",
            level
        );
    }
}
