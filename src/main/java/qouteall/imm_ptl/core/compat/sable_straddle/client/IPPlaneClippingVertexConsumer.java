/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3fc;

/**
 * Clips quads or lines at a plane on the CPU: for geometry drawn in a batch after the per-ship clip plane is gone (block
 * entities of a straddling ship), or with a shader IP's portal plane does not reach. Collects each primitive's vertices
 * (position as received, i.e. already transformed, and whatever colour, texture, overlay, light and normal the caller
 * sets), clips it against {@code normal . (v - anchor) >= 0} (Sutherland-Hodgman, attributes interpolated) and emits the
 * rest: a quad as one or two quads, a line as a shorter line. Only for quad and line buffers; call {@link #flush} after
 * the last vertex.
 */
public final class IPPlaneClippingVertexConsumer implements VertexConsumer {
    private static final int MAX = 8;

    private final VertexConsumer delegate;
    private final float normalX;
    private final float normalY;
    private final float normalZ;
    private final float anchorDot;
    /** Vertices per primitive: 4 (quads) or 2 (lines). */
    private final int vertices;

    private final float[][] position = new float[4][3];
    private final int[][] color = new int[4][4];
    private final float[][] uv = new float[4][2];
    private final int[][] uv1 = new int[4][2];
    private final int[][] uv2 = new int[4][2];
    private final float[][] normal = new float[4][3];
    private boolean hasColor;
    private boolean hasUv;
    private boolean hasUv1;
    private boolean hasUv2;
    private boolean hasNormal;
    /** Vertices of the primitive being collected; the last one may still receive attributes. */
    private int count;
    // scratch for emitQuad: signed distances and the clipped polygon as pairs (a, b, t), the point a + t (b - a)
    private final float[] d = new float[4];
    private final int[] from = new int[MAX];
    private final int[] to = new int[MAX];
    private final float[] at = new float[MAX];

    public IPPlaneClippingVertexConsumer(final VertexConsumer delegate, final Vector3fc normal, final Vector3fc anchor) {
        this(delegate, normal, anchor, 4);
    }

    /**
     * @param vertices vertices per primitive: 4 for quad buffers, 2 for line buffers
     */
    public IPPlaneClippingVertexConsumer(final VertexConsumer delegate, final Vector3fc normal, final Vector3fc anchor, final int vertices) {
        this.delegate = delegate;
        this.normalX = normal.x();
        this.normalY = normal.y();
        this.normalZ = normal.z();
        this.anchorDot = normal.dot(anchor);
        this.vertices = vertices;
    }

    @Override
    public VertexConsumer addVertex(final float x, final float y, final float z) {
        if (this.count == this.vertices) {
            this.emitPrimitive();
        }
        final float[] p = this.position[this.count];
        p[0] = x;
        p[1] = y;
        p[2] = z;
        this.count++;
        return this;
    }

    @Override
    public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha) {
        final int[] c = this.color[this.count - 1];
        c[0] = red;
        c[1] = green;
        c[2] = blue;
        c[3] = alpha;
        this.hasColor = true;
        return this;
    }

    @Override
    public VertexConsumer setUv(final float u, final float v) {
        final float[] t = this.uv[this.count - 1];
        t[0] = u;
        t[1] = v;
        this.hasUv = true;
        return this;
    }

    @Override
    public VertexConsumer setUv1(final int u, final int v) {
        final int[] t = this.uv1[this.count - 1];
        t[0] = u;
        t[1] = v;
        this.hasUv1 = true;
        return this;
    }

    @Override
    public VertexConsumer setUv2(final int u, final int v) {
        final int[] t = this.uv2[this.count - 1];
        t[0] = u;
        t[1] = v;
        this.hasUv2 = true;
        return this;
    }

    @Override
    public VertexConsumer setNormal(final float x, final float y, final float z) {
        final float[] n = this.normal[this.count - 1];
        n[0] = x;
        n[1] = y;
        n[2] = z;
        this.hasNormal = true;
        return this;
    }

    /** Emits the last primitive; call after the caller is done with this consumer. */
    public void flush() {
        if (this.count == this.vertices) {
            this.emitPrimitive();
        }
        this.count = 0;
    }

    private void emitPrimitive() {
        if (this.vertices == 2) {
            this.emitLine();
        } else {
            this.emitQuad();
        }
    }

    private void emitLine() {
        this.count = 0;
        final float d0 = this.distance(this.position[0]);
        final float d1 = this.distance(this.position[1]);
        if (d0 < 0.0f && d1 < 0.0f) {
            return;
        }
        if (d0 >= 0.0f && d1 >= 0.0f) {
            this.emitVertex(0, 0, 0.0f);
            this.emitVertex(1, 1, 0.0f);
            return;
        }
        final float t = d0 / (d0 - d1);
        if (d0 >= 0.0f) {
            this.emitVertex(0, 0, 0.0f);
            this.emitVertex(0, 1, t);
        } else {
            this.emitVertex(0, 1, t);
            this.emitVertex(1, 1, 0.0f);
        }
    }

    private float distance(final float[] p) {
        return this.normalX * p[0] + this.normalY * p[1] + this.normalZ * p[2] - this.anchorDot;
    }

    private void emitQuad() {
        this.count = 0;
        final float[] d = this.d;
        boolean allIn = true;
        boolean allOut = true;
        for (int i = 0; i < 4; i++) {
            d[i] = this.distance(this.position[i]);
            allIn &= d[i] >= 0.0f;
            allOut &= d[i] < 0.0f;
        }
        if (allOut) {
            return;
        }
        if (allIn) {
            for (int i = 0; i < 4; i++) {
                this.emitVertex(i, i, 0.0f);
            }
            return;
        }
        final int[] from = this.from;
        final int[] to = this.to;
        final float[] at = this.at;
        int n = 0;
        for (int i = 0; i < 4; i++) {
            final int j = (i + 1) & 3;
            if (d[i] >= 0.0f) {
                from[n] = i;
                to[n] = i;
                at[n++] = 0.0f;
            }
            if ((d[i] >= 0.0f) != (d[j] >= 0.0f)) {
                from[n] = i;
                to[n] = j;
                at[n++] = d[i] / (d[i] - d[j]);
            }
        }
        if (n < 3) {
            return;
        }
        // as quads: a fan from the first point, the last quad of an odd count repeats its last point
        for (int start = 1; start + 1 < n; start += 2) {
            this.emitVertex(from[0], to[0], at[0]);
            this.emitVertex(from[start], to[start], at[start]);
            this.emitVertex(from[start + 1], to[start + 1], at[start + 1]);
            final int last = Math.min(start + 2, n - 1);
            this.emitVertex(from[last], to[last], at[last]);
        }
    }

    private void emitVertex(final int a, final int b, final float t) {
        final float[] pa = this.position[a];
        final float[] pb = this.position[b];
        final VertexConsumer out = this.delegate.addVertex(lerp(pa[0], pb[0], t), lerp(pa[1], pb[1], t), lerp(pa[2], pb[2], t));
        if (this.hasColor) {
            final int[] ca = this.color[a];
            final int[] cb = this.color[b];
            out.setColor(Math.round(lerp(ca[0], cb[0], t)), Math.round(lerp(ca[1], cb[1], t)),
                    Math.round(lerp(ca[2], cb[2], t)), Math.round(lerp(ca[3], cb[3], t)));
        }
        if (this.hasUv) {
            out.setUv(lerp(this.uv[a][0], this.uv[b][0], t), lerp(this.uv[a][1], this.uv[b][1], t));
        }
        if (this.hasUv1) {
            out.setUv1(Math.round(lerp(this.uv1[a][0], this.uv1[b][0], t)), Math.round(lerp(this.uv1[a][1], this.uv1[b][1], t)));
        }
        if (this.hasUv2) {
            out.setUv2(Math.round(lerp(this.uv2[a][0], this.uv2[b][0], t)), Math.round(lerp(this.uv2[a][1], this.uv2[b][1], t)));
        }
        if (this.hasNormal) {
            float nx = lerp(this.normal[a][0], this.normal[b][0], t);
            float ny = lerp(this.normal[a][1], this.normal[b][1], t);
            float nz = lerp(this.normal[a][2], this.normal[b][2], t);
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1.0e-6f) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
            out.setNormal(nx, ny, nz);
        }
    }

    private static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }
}
