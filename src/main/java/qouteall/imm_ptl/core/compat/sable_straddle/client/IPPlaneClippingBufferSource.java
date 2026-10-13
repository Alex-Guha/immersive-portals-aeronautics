/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * A buffer source whose quad and line buffers clip at a plane ({@link IPPlaneClippingVertexConsumer}). A batched source
 * may finish one render type's buffer when another is asked for, so pending primitives are emitted before every
 * request, and {@link #flush} emits the last ones; other primitive modes pass through unclipped. Sources can be nested
 * to clip at several planes; {@link #flush} on the outer one flushes the inner ones too.
 */
public final class IPPlaneClippingBufferSource implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final Vector3fc normal;
    private final Vector3fc anchor;
    private final List<IPPlaneClippingVertexConsumer> open = new ArrayList<>(2);

    public IPPlaneClippingBufferSource(final MultiBufferSource delegate, final Vector3fc normal, final Vector3fc anchor) {
        this.delegate = delegate;
        this.normal = normal;
        this.anchor = anchor;
    }

    @Override
    public VertexConsumer getBuffer(final RenderType renderType) {
        this.flush();
        final VertexConsumer buffer = this.delegate.getBuffer(renderType);
        final VertexFormat.Mode mode = renderType.mode();
        final int vertices;
        if (mode == VertexFormat.Mode.QUADS) {
            vertices = 4;
        } else if (mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.DEBUG_LINES) {
            vertices = 2;
        } else {
            return buffer;
        }
        final IPPlaneClippingVertexConsumer clipping = new IPPlaneClippingVertexConsumer(buffer, this.normal, this.anchor, vertices);
        this.open.add(clipping);
        return clipping;
    }

    public void flush() {
        for (final IPPlaneClippingVertexConsumer consumer : this.open) {
            consumer.flush();
        }
        this.open.clear();
        if (this.delegate instanceof final IPPlaneClippingBufferSource inner) {
            inner.flush();
        }
    }
}
