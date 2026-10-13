/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3fc;

/**
 * Cuts a straight prism (a rope segment) exactly at a plane: every vertex on the dropped side is slid along the prism's
 * axis onto the plane, so the prism ends in the plane instead of square to its axis. Positions are those the wrapped
 * consumer receives (already transformed).
 */
public final class IPPlaneClampingVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float normalX;
    private final float normalY;
    private final float normalZ;
    private final Vector3fc anchor;
    private final Vector3fc axis;
    private final float axisRate;

    /**
     * @param normal kept side of the plane ({@code normal . (v - anchor) >= 0})
     * @param anchor a point of the plane
     * @param axis   the prism's axis direction
     */
    public IPPlaneClampingVertexConsumer(final VertexConsumer delegate, final Vector3fc normal, final Vector3fc anchor, final Vector3fc axis) {
        this.delegate = delegate;
        this.normalX = normal.x();
        this.normalY = normal.y();
        this.normalZ = normal.z();
        this.anchor = anchor;
        this.axis = axis;
        this.axisRate = normal.dot(axis);
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        final float distance = this.normalX * (x - this.anchor.x()) + this.normalY * (y - this.anchor.y()) + this.normalZ * (z - this.anchor.z());
        if (distance >= 0.0f) {
            this.delegate.addVertex(x, y, z);
        } else {
            final float slide = -distance / this.axisRate;
            this.delegate.addVertex(x + this.axis.x() * slide, y + this.axis.y() * slide, z + this.axis.z() * slide);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha) {
        this.delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        this.delegate.setNormal(x, y, z);
        return this;
    }
}
