/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

/**
 * Whether the current thread is deciding, for a section being compiled, if a block entity becomes a Flywheel visual
 * (bugs.md #8). Flywheel asks {@code supportsVisualization(level)}, which compares with {@code Minecraft.level}; compiles
 * run on worker threads while IP's portal passes swap that field, so a section of a world seen through a portal (or of
 * the player's world, compiled during a pass) was decided by whichever world was being drawn. A part kept out of
 * Flywheel there was then skipped by Create's renderer too (which asks the same question while its world is drawn),
 * and stayed invisible until its section compiled again at a luckier moment. Inside this scope a client world that
 * already has a Flywheel manager counts as supported ({@code MixinFlywheelVisualizationManager_SectionWorld}).
 */
public final class IPFlywheelSectionWorld {
    private static final ThreadLocal<Boolean> DECIDING = ThreadLocal.withInitial(() -> false);

    private IPFlywheelSectionWorld() {
    }

    public static boolean begin() {
        boolean outer = DECIDING.get();
        DECIDING.set(true);
        return outer;
    }

    public static void end(boolean outer) {
        DECIDING.set(outer);
    }

    public static boolean isDeciding() {
        return DECIDING.get();
    }
}
