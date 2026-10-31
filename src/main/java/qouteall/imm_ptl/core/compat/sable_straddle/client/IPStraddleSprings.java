/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Aeronautics' springs at a portal. A spring is drawn by the block entity at one end, its controller, as a curve to the
 * other end, which may be on another ship: while the curve passes through a portal it is drawn by the curve and not by
 * the block's centre, in the ship's own world cut at the plane and in the destination mapped through the portal, cut at
 * the other side of the plane, whichever of the two ships has the session. Unlike ropes, the curve is one mesh, so it is
 * cut as a whole on the CPU (its Veil shader has neither of our planes). Only the controller's own ship is ever drawn
 * mapped ({@link IPStraddleRender#realRenderPose} keeps the other end right). A spring whose other end is in the world
 * is not cut: it snaps when its ship crosses.
 */
public final class IPStraddleSprings {
    private static final boolean SIMULATED_LOADED = ModList.get().isLoaded("simulated");

    private IPStraddleSprings() {
    }

    /** @return the session of the portal the spring drawn by {@code blockEntity} passes through now, or null */
    public static IPStraddleClientSessions.@Nullable Session crossingSession(final BlockEntity blockEntity) {
        if (!SIMULATED_LOADED || IPStraddleClientSessions.isEmpty()) {
            return null;
        }
        return Impl.crossingSession(blockEntity);
    }

    /** Whether the current pass shows nothing of a spring crossing {@code session}'s portal. */
    public static boolean hides(final BlockEntity blockEntity, final IPStraddleClientSessions.Session session) {
        return Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel ship
                && IPStraddleRender.facesPortalClipAway(keptSide(ship, session));
    }

    /** A buffer source keeping what the current pass shows of a spring crossing {@code session}'s portal. */
    public static MultiBufferSource clippingSource(final BlockEntity blockEntity, final IPStraddleClientSessions.Session session,
                                                   final PoseStack poseStack, final MultiBufferSource source) {
        if (Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel ship) {
            return IPStraddleRender.clippingSourceAt(ship, blockEntity.getBlockPos(), poseStack, source, keptSide(ship, session));
        }
        return source;
    }

    /** The ship's own side of the portal, or the far side while {@code ship} is drawn mapped through it. */
    private static IPStraddleRender.KeepPlane keptSide(final ClientSubLevel ship, final IPStraddleClientSessions.Session session) {
        final IPStraddleRender.KeepPlane projected = IPStraddleRender.projectionSession(ship) != null ? IPStraddleRender.planeFor(ship) : null;
        return projected != null ? projected : new IPStraddleRender.KeepPlane(session.origin(), session.normal().scale(-1.0));
    }

    /**
     * In the ship's destination: draws the far part of springs whose controller is on a ship without a session at this
     * portal, tied to a ship that has one. Their ship has no far half of its own to draw them with.
     */
    public static void drawFarParts(final SubLevelRenderDispatcher.BlockEntityRenderer renderer,
                                    final double cameraX, final double cameraY, final double cameraZ, final float partialTick) {
        final ClientLevel rendering = Minecraft.getInstance().level;
        if (!SIMULATED_LOADED || rendering == null || IPStraddleRender.isProjecting() || IPStraddleClientSessions.isEmpty()) {
            return;
        }
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsInto(rendering.dimension())) {
            final ClientSubLevel ship = IPStraddleRender.shipOf(session);
            if (ship != null) {
                IPStraddleRopes.forEachBlockEntity(ship, partner -> Impl.drawFarPart(partner, session, renderer, cameraX, cameraY, cameraZ, partialTick));
            }
        }
    }

    private static final class Impl {
        static IPStraddleClientSessions.@Nullable Session crossingSession(final BlockEntity blockEntity) {
            if (!(blockEntity instanceof final dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity spring) || !spring.isController()
                    || !(Sable.HELPER.getContaining(spring) instanceof final ClientSubLevel ship)) {
                return null;
            }
            final var other = spring.getPairedSpring();
            if (other == null) {
                return null;
            }
            final SubLevel otherShip = Sable.HELPER.getContaining(other);
            final Vector3dc from = worldCentre(ship, spring.getCenter());
            final Vector3dc to = otherShip instanceof final ClientSubLevel client ? worldCentre(client, other.getCenter()) : other.getCenter();
            final IPStraddleClientSessions.Session projected = IPStraddleRender.projectionSession(ship);
            if (projected != null) {
                return IPStraddleRopes.throughInterval(projected, from, to) != null ? projected : null;
            }
            for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(ship)) {
                if (IPStraddleRopes.throughInterval(session, from, to) != null) {
                    return session;
                }
            }
            if (otherShip != null) {
                for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(otherShip)) {
                    if (IPStraddleRopes.throughInterval(session, from, to) != null) {
                        return session;
                    }
                }
            }
            return null;
        }

        private static Vector3dc worldCentre(final ClientSubLevel ship, final Vector3dc plotCentre) {
            return IPStraddleRender.realRenderPose(ship).transformPosition(plotCentre, new Vector3d());
        }

        static void drawFarPart(final BlockEntity partner, final IPStraddleClientSessions.Session session,
                                final SubLevelRenderDispatcher.BlockEntityRenderer renderer,
                                final double cameraX, final double cameraY, final double cameraZ, final float partialTick) {
            if (!(partner instanceof final dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity spring) || spring.isController()) {
                return;
            }
            final var controller = spring.getPairedSpring();
            if (controller == null || !(Sable.HELPER.getContaining(controller) instanceof final ClientSubLevel ship)
                    || IPStraddleClientSessions.renderSessionsOf(ship).stream().anyMatch(own -> own.portal().equals(session.portal()))) {
                return;
            }
            IPStraddleRender.withProjection(ship, session, () -> IPStraddleRopes.drawProjected(ship, renderer, cameraX, cameraY, cameraZ, partialTick,
                    blockEntity -> blockEntity == controller));
        }
    }
}
