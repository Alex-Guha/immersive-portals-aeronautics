/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;

import java.util.List;
import java.util.Queue;

/**
 * Particles of the far half of a straddling ship go to the world the far half is in. Sable moves a particle spawned on
 * a ship (plot space) to the ship's world position and keeps it moving with the ship; for a block past the portal
 * plane that is a spot of the ship's own world behind the portal, where nothing of the ship is drawn (a burner's smoke
 * and flames showed on the wrong side of the portal). Once per client tick, a particle of the ship's world that is past
 * the plane, within the opening and on (or tracking) the ship is moved through the portal: position, last position and
 * velocity mapped, plus the ship's velocity at that point if it was carried by the ship. It then ticks and is drawn in
 * the destination like any other particle there.
 */
public final class IPStraddleParticles {
    /** Lateral slack around the opening, as the physics clip regions (blocks). */
    private static final double APERTURE_MARGIN = 0.5;
    /** How far around the ship a particle counts as its own (blocks). */
    private static final double SHIP_MARGIN = 2.0;

    private IPStraddleParticles() {
    }

    public static void tick() {
        if (IPStraddleClientSessions.isEmpty()) {
            return;
        }
        final List<IPStraddleClientSessions.Session> sessions = IPStraddleClientSessions.renderSessions();
        if (sessions.isEmpty()) {
            return;
        }
        for (final Queue<Particle> queue : ((IEParticleManager) Minecraft.getInstance().particleEngine).ip_getParticles().values()) {
            for (final Particle particle : queue) {
                final ClientLevel level = ((IEParticle) particle).portal_getWorld();
                for (final IPStraddleClientSessions.Session session : sessions) {
                    if (session.shipDim().equals(level.dimension()) && moveThrough(particle, level, session)) {
                        break;
                    }
                }
            }
        }
    }

    private static boolean moveThrough(final Particle particle, final ClientLevel level, final IPStraddleClientSessions.Session session) {
        final Vec3 center = particle.getBoundingBox().getCenter();
        final Vec3 offset = center.subtract(session.origin());
        if (offset.dot(session.normal()) <= 0.0
            || Math.abs(offset.dot(session.axisW())) > session.halfW() + APERTURE_MARGIN
            || Math.abs(offset.dot(session.axisH())) > session.halfH() + APERTURE_MARGIN) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(session.ship());
        if (ship == null) {
            return false;
        }
        final ParticleExtension extension = (ParticleExtension) particle;
        final boolean carried = extension.sable$getTrackingSubLevel() == ship;
        final AABB around = new AABB(ship.boundingBox().minX(), ship.boundingBox().minY(), ship.boundingBox().minZ(),
            ship.boundingBox().maxX(), ship.boundingBox().maxY(), ship.boundingBox().maxZ()).inflate(SHIP_MARGIN);
        if (!carried && !around.contains(center)) {
            return false;
        }
        final ClientLevel dest = ClientWorldLoader.getOptionalWorld(session.destDim());
        if (dest == null) {
            return false;
        }

        final IEParticle fields = (IEParticle) particle;
        Vec3 velocity = new Vec3(fields.ip_getXd(), fields.ip_getYd(), fields.ip_getZd());
        if (carried) {
            // Sable stops carrying it: give it the ship's velocity there (m/s to blocks per tick)
            final Vector3d local = ship.logicalPose().transformPositionInverse(new Vector3d(center.x, center.y, center.z));
            final Vector3d shipVelocity = Sable.HELPER.getVelocity(level, local, new Vector3d());
            velocity = velocity.add(shipVelocity.x / 20.0, shipVelocity.y / 20.0, shipVelocity.z / 20.0);
        }
        final IPPortalIsometry isometry = session.isometry();
        final Vec3 position = isometry.mapPoint(center.subtract(0.0, particle.getBoundingBox().getYsize() * 0.5, 0.0));
        final Vec3 last = isometry.mapPoint(new Vec3(fields.ip_getXo(), fields.ip_getYo(), fields.ip_getZo()));
        final Vec3 mappedVelocity = isometry.mapVec(velocity);

        extension.sable$setTrackingSubLevel(null, position);
        fields.ip_setWorld(dest);
        particle.setPos(position.x, position.y, position.z);
        fields.ip_setXo(last.x);
        fields.ip_setYo(last.y);
        fields.ip_setZo(last.z);
        fields.ip_setXd(mappedVelocity.x);
        fields.ip_setYd(mappedVelocity.y);
        fields.ip_setZd(mappedVelocity.z);
        return true;
    }
}
