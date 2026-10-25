/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

/**
 * An entity IP never teleports through a portal on its own: another mod moves it across (Create's carriage copies, which
 * Create places and replaces itself; a copy IP carried to the other side would leave Create's record of it dangling).
 */
public interface IPPortalTeleportExempt {
}
