/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.multiplayer.ClientLevel;

public interface IEParticleManager {
    void ip_setWorld(ClientLevel world);
    
    // Immersive Aeronautics: ticks the particles of the engine's current world only (no emitters, no queued additions)
    void ip_tickParticleLists();
    
    // Immersive Aeronautics: every particle, by render type
    java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> ip_getParticles();
}
