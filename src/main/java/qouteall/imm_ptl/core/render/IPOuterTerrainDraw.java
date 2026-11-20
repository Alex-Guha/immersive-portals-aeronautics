/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

/**
 * Set while the player's world's translucent terrain is drawn inside a portal view (IPNearTranslucents). Sable draws a
 * world's ships at the end of every Sodium terrain draw, so that draw would bring the player's world's ships along with
 * the wrong clip; they are drawn by IPStraddleRender.forEachNearShip instead, and Sable skips them meanwhile.
 */
public final class IPOuterTerrainDraw {
    private static boolean active;

    private IPOuterTerrainDraw() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }
}
