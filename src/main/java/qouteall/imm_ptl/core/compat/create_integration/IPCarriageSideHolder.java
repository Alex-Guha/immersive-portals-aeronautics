/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

/**
 * A Create carriage copy's side key as the server gave it ({@link IPCreateTrainSides}): empty for a copy keyed by its
 * level's dimension. Synced to clients.
 */
public interface IPCarriageSideHolder {
    String ip_getTrainSide();

    void ip_setTrainSide(String side);
}
