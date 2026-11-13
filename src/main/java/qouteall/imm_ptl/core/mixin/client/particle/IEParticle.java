/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface IEParticle {
    @Accessor("level")
    ClientLevel portal_getWorld();
    
    // Immersive Aeronautics: moving a particle into another world (the far half of a straddling ship, IPStraddleParticles)
    @org.spongepowered.asm.mixin.Mutable
    @Accessor("level")
    void ip_setWorld(ClientLevel world);
    
    @Accessor("xo")
    double ip_getXo();
    
    @Accessor("yo")
    double ip_getYo();
    
    @Accessor("zo")
    double ip_getZo();
    
    @Accessor("xo")
    void ip_setXo(double value);
    
    @Accessor("yo")
    void ip_setYo(double value);
    
    @Accessor("zo")
    void ip_setZo(double value);
    
    @Accessor("xd")
    double ip_getXd();
    
    @Accessor("yd")
    double ip_getYd();
    
    @Accessor("zd")
    double ip_getZd();
    
    @Accessor("xd")
    void ip_setXd(double value);
    
    @Accessor("yd")
    void ip_setYd(double value);
    
    @Accessor("zd")
    void ip_setZd(double value);
}
