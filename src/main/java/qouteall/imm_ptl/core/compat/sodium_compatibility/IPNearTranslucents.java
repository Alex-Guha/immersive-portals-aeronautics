/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.q_misc_util.my_util.Plane;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumSortedRenderLists;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.IPOuterTerrainDraw;
import qouteall.imm_ptl.core.render.IPPortalChain;
import qouteall.imm_ptl.core.render.IPScreenRect;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Arrays;
import java.util.List;

/**
 * Translucent terrain in front of a portal (water, stained glass, ice) with a shader pack. {@link IrisPortalRenderer}
 * draws a portal's view after the pack has finished this world's image, and the view covers what was translucent in
 * front of the portal (the portal's area is decided against the depth without translucents, IPIrisTranslucentDepth),
 * so tinted glass and water surfaces vanished over the portal. They are drawn again inside the view instead: right after
 * the view's own translucent terrain, the player's world's translucent sections are drawn through the portal (its
 * camera, its render lists, a model-view matrix carrying them through the portal), kept on the camera's side of the
 * portal and to the portal's rectangle on screen. The pack draws them with the view's programs, so they sit over the
 * view as they sit over the world beside the portal.
 * <p>
 * The player's world's ships are not drawn by that terrain draw (Sable would add them, {@link IPOuterTerrainDraw}) but by
 * {@code IPStraddleRender.forEachNearShip}. A view inside a view draws the translucents of every layer around it
 * ({@link #drawOuterLayers}); an outer view's are also cut where that view cut them (beyond its own portal), on a second
 * clip plane of the pack's Sodium terrain programs ({@code ipa_OuterClipEquation}). Chains through a mirror are left out
 * (a reflection turns the faces around).
 */
public final class IPNearTranslucents {
    /** A pass's translucent terrain: its renderer, render lists and camera, in the world it drew. */
    private record Pass(SodiumWorldRenderer renderer, SortedRenderLists lists, ResourceKey<Level> dimension,
                        double x, double y, double z) {
    }

    /** Per portal layer, the last pass's translucent terrain this frame; a view draws those of every layer around it. */
    private static Pass[] passes = new Pass[0];
    /** Set while the outer world's terrain is drawn, so that draw is neither recorded nor drawn again. */
    private static boolean drawing;

    private IPNearTranslucents() {
    }

    /** End of {@code SodiumWorldRenderer.drawChunkLayer}: record the pass's translucent terrain, and in a view draw the outer world's. */
    public static void afterDrawChunkLayer(SodiumWorldRenderer renderer, RenderType type, ChunkRenderMatrices matrices,
                                           double x, double y, double z) {
        if (drawing || type != RenderType.translucent() || IPCGlobal.renderer != IrisPortalRenderer.instance
            || IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        int layer = PortalRendering.getPortalLayer();
        if (passes.length <= layer) {
            passes = Arrays.copyOf(passes, layer + 1);
        }
        RenderSectionManager manager = ((IESodiumWorldRenderer) renderer).ip_getRenderSectionManager();
        passes[layer] = new Pass(renderer, manager.getRenderLists(), minecraft.level.dimension(), x, y, z);
        if (layer >= 1) {
            drawOuterLayers(layer, matrices, new Vec3(x, y, z));
        }
    }

    /**
     * Every outer layer's translucent terrain in front of its portal, farthest first: the layer around this view, then
     * the one around that, out to the player's world. A view inside a view is pasted over the outer view's image after
     * that view is finished, so it has to carry the glass in front of every portal on the way to it.
     */
    private static void drawOuterLayers(int layer, ChunkRenderMatrices matrices, Vec3 camera) {
        if (!FrontClipping.isClippingEnabled) {
            return;
        }
        int[] scissor = IPScreenRect.ofPortalView(PortalRendering.getRenderingPortal(), matrices.projection(),
            matrices.modelView(), camera);
        if (IPScreenRect.isEmpty(scissor)) {
            return;
        }
        for (int j = layer - 1; j >= 0; j--) {
            if (j < passes.length && passes[j] != null) {
                drawLayer(j, passes[j], matrices, camera, scissor);
            }
        }
    }

    /**
     * The translucent terrain pass {@code j} drew, in front of the portal it looks through, carried through the portals
     * from there to this view and kept on the camera's side of that portal; for an outer view ({@code j >= 1}) also only
     * what that view kept (beyond its own portal), on the second plane.
     */
    private static void drawLayer(int j, Pass outer, ChunkRenderMatrices matrices, Vec3 camera, int @Nullable [] scissor) {
        List<Portal> chain = IPPortalChain.from(j);
        Portal through = chain.get(0);
        if (IPPortalChain.hasMirror(chain) || through.getOriginWorld() == null
            || through.getOriginWorld().dimension() != outer.dimension()) {
            return;
        }
        Plane nearPlane = IPPortalChain.nearPlane(j);
        Plane outerPlane = IPPortalChain.outerPlane(j);
        if (nearPlane == null || outerPlane == IPPortalChain.NO_PLANE) {
            return;
        }
        Matrix4f view = new Matrix4f(matrices.modelView());
        double[] clip = FrontClipping.eyeSpaceEquation(nearPlane, view);
        double[] outerClip = outerPlane == null ? null : FrontClipping.eyeSpaceEquation(outerPlane, view);
        
        // outer camera-relative positions -> through the portals -> relative to this pass's camera
        Vec3 c0 = IPPortalChain.mapVector(chain, new Vec3(1, 0, 0));
        Vec3 c1 = IPPortalChain.mapVector(chain, new Vec3(0, 1, 0));
        Vec3 c2 = IPPortalChain.mapVector(chain, new Vec3(0, 0, 1));
        Matrix4f linear = new Matrix4f(
            (float) c0.x, (float) c0.y, (float) c0.z, 0,
            (float) c1.x, (float) c1.y, (float) c1.z, 0,
            (float) c2.x, (float) c2.y, (float) c2.z, 0,
            0, 0, 0, 1
        );
        Vec3 offset = IPPortalChain.mapPoint(chain, new Vec3(outer.x(), outer.y(), outer.z())).subtract(camera);
        Matrix4f modelView = new Matrix4f(view)
            .translate((float) offset.x, (float) offset.y, (float) offset.z)
            .mul(linear);
        
        SortedRenderLists near = nearRegions(outer, through);
        if (near == null) {
            return;
        }
        
        RenderSectionManager manager = ((IESodiumWorldRenderer) outer.renderer()).ip_getRenderSectionManager();
        SortedRenderLists current = manager.getRenderLists();
        drawing = true;
        IPOuterTerrainDraw.setActive(true);
        double[] previousClip = FrontClipping.swapTerrainClipEquation(clip);
        if (outerClip != null) {
            FrontClipping.setOuterTerrainClipEquation(outerClip);
            GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        }
        try {
            // the outer pass's lists, cut down (in a portal into the same world, the renderer holds a later pass's)
            ((IESodiumRenderSectionManager) manager).ip_setRenderLists(near);
            if (scissor != null) {
                RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            }
            outer.renderer().drawChunkLayer(
                RenderType.translucent(), new ChunkRenderMatrices(matrices.projection(), modelView),
                outer.x(), outer.y(), outer.z()
            );
        }
        finally {
            if (scissor != null) {
                RenderSystem.disableScissor();
            }
            ((IESodiumRenderSectionManager) manager).ip_setRenderLists(current);
            FrontClipping.swapTerrainClipEquation(previousClip);
            if (outerClip != null) {
                GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
                FrontClipping.setOuterTerrainClipEquation(null);
            }
            IPOuterTerrainDraw.setActive(false);
            drawing = false;
        }
    }

    /**
     * @return the outer pass's render lists with only the regions that can hold something between its camera and the
     * portal: those touching the box around the camera and the portal (the space they span lies inside it), in the same
     * order and as the same per-region lists, so Sodium's cached draw batches stay valid; null when there is none.
     * Regions are 128 x 64 x 128 blocks; the clip plane and the scissor cut the rest.
     */
    private static @Nullable SortedRenderLists nearRegions(Pass outer, Portal portal) {
        AABB span = portal.getBoundingBox()
            .minmax(new AABB(outer.x(), outer.y(), outer.z(), outer.x(), outer.y(), outer.z()))
            .inflate(1.0);
        ObjectArrayList<ChunkRenderList> all = ((IESodiumSortedRenderLists) outer.lists()).ip_getLists();
        ObjectArrayList<ChunkRenderList> kept = new ObjectArrayList<>();
        for (ChunkRenderList list : all) {
            RenderRegion region = list.getRegion();
            double x = region.getOriginX();
            double y = region.getOriginY();
            double z = region.getOriginZ();
            if (x <= span.maxX && x + REGION_X >= span.minX && y <= span.maxY && y + REGION_Y >= span.minY
                && z <= span.maxZ && z + REGION_Z >= span.minZ) {
                kept.add(list);
            }
        }
        return kept.isEmpty() ? null : IESodiumSortedRenderLists.ip_create(kept);
    }
    
    private static final int REGION_X = RenderRegion.REGION_WIDTH * 16;
    private static final int REGION_Y = RenderRegion.REGION_HEIGHT * 16;
    private static final int REGION_Z = RenderRegion.REGION_LENGTH * 16;
    
    /** Forgets every recorded pass (start of a frame). */
    public static void clearAll() {
        Arrays.fill(passes, null);
    }
}
