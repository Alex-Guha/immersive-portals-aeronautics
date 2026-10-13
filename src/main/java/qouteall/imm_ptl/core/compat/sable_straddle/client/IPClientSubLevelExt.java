/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

/**
 * Implemented by Sable's {@code ClientSubLevel} through {@code MixinSableClientSubLevel_StraddlePose}.
 */
public interface IPClientSubLevelExt {
    /** Forgets the cached render pose, after its last and logical poses were set from outside. */
    void ip_invalidateRenderPose();
}
