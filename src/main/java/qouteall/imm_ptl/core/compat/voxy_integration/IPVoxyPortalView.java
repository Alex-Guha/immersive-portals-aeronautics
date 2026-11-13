/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.voxy_integration;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

/**
 * Voxy (through Roxy, or Neo Voxy) inside portal views.
 * <p>
 * Voxy keeps one render system per {@code LevelRenderer}, and IP one level renderer per dimension, so a portal pass
 * into another dimension draws that dimension's own LODs from the portal camera (the {@code mixin_voxy} mixins let it
 * run there). Voxy hides LODs wherever that dimension has built Sodium sections, so they start where the terrain the
 * pass draws ends. Each portal draws with a viewport of its own ({@code MixinVoxyRenderSystem_PortalView}).
 * <p>
 * What this class does:
 * <ul>
 * <li>Keeps IP's stencil settings across Voxy's draw, which leaves the stencil test off; they are read at the start of
 * Sodium's cutout pass while Minecraft's framebuffer is bound (Roxy's own framebuffer has no stencil, and Mesa clamps
 * a reference read there).</li>
 * <li>Limits Voxy's composite (the one draw into Minecraft's framebuffer) to the portal in portal passes. Roxy binds an
 * adapter framebuffer with Minecraft's depth texture at the depth attachment only, so the same depth-stencil texture
 * is attached as its stencil for the pass.</li>
 * <li>Tells whether a Voxy render-generation service is the one Roxy tracks. Roxy follows a single renderer (the
 * player's dimension) and discards every other renderer's meshes as stale, so a dimension seen through a portal never
 * got any; those are handed to Voxy directly, as stock Voxy does.</li>
 * <li>Names the level a Voxy render system is being made for (the level renderer's in {@code allChanged}, else the
 * player's: Roxy defers only the player's renderer), so it binds to that dimension's Iris pipeline
 * ({@code IPVoxyIrisPipelines}).</li>
 * </ul>
 * Neo Voxy has no Roxy: it composites straight into Minecraft's framebuffer, which has the stencil, and keeps every
 * render system's meshes. No Voxy or Roxy types here: Roxy's classes live in NeoForge's service layer, out of Mixin's
 * reach, and are read by reflection.
 */
public final class IPVoxyPortalView {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Master switch for Voxy LODs inside portal views. */
    public static volatile boolean ENABLED = true;

    private static boolean saved;
    private static boolean stencilTest;
    private static int func, ref, valueMask, writeMask, sfail, dpfail, dppass;

    private static int stencilAttachedTo;
    private static boolean loggedDrawn;
    private static boolean loggedNoStencil;

    // read from Voxy's mesh worker threads
    private static volatile Field roxyActiveService;
    private static volatile boolean roxyLookupDone;

    private static final Object NO_LEVEL = new Object();
    private static final ArrayDeque<Object> CREATION_LEVELS = new ArrayDeque<>();

    private IPVoxyPortalView() {
    }

    /** Around anything that may make a Voxy render system for {@code level} (render thread). */
    public static void beginRendererCreation(@Nullable ClientLevel level) {
        CREATION_LEVELS.push(level != null ? level : NO_LEVEL);
    }

    public static void endRendererCreation() {
        CREATION_LEVELS.poll();
    }

    /**
     * True while {@code Minecraft.level} is not the player's world: a portal pass into another dimension, or a packet
     * of another world being handled. Neo Voxy's distant extras (tracks, kinetics, beacons, lasers) are single global
     * renderers holding the player's world only, so they stand aside then.
     */
    public static boolean isOtherWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.player != null && minecraft.level != minecraft.player.level();
    }

    /** The level a Voxy render system is being made for. */
    public static @Nullable ClientLevel creationLevel() {
        Object level = CREATION_LEVELS.peek();
        if (level == null) {
            return Minecraft.getInstance().level;
        }
        return level instanceof ClientLevel clientLevel ? clientLevel : null;
    }

    /** At the start of Sodium's cutout pass, before Voxy draws at its end. */
    public static void saveStencil() {
        stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        func = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        ref = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        valueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        writeMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        sfail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        dpfail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        dppass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        saved = true;
    }

    /** At the end of Sodium's cutout pass. */
    public static void restoreStencil() {
        if (!saved) {
            return;
        }
        saved = false;
        if (stencilTest) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        }
        else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        GL11.glStencilFunc(func, ref, valueMask);
        GL11.glStencilOp(sfail, dpfail, dppass);
        GL11.glStencilMask(writeMask);
    }

    /**
     * At the start of Voxy's draw in a portal pass, with Roxy's framebuffer bound. Returns false when the composite
     * cannot be limited to the portal; Voxy is then skipped in the pass.
     */
    public static boolean beginPortalPass() {
        if (!saved || !stencilTest) {
            return false;
        }
        if (!ensureStencilAttached()) {
            if (!loggedNoStencil) {
                loggedNoStencil = true;
                LOGGER.warn("[IPVoxy] Voxy's framebuffer has no stencil; Voxy LODs are not drawn in portal views");
            }
            return false;
        }
        if (!loggedDrawn) {
            loggedDrawn = true;
            LOGGER.info("[IPVoxy] Voxy LODs drawn in a portal view");
        }
        return true;
    }

    /** At the end of Voxy's draw in a portal pass: Roxy's framebuffer goes back to depth only. */
    public static void endPortalPass() {
        if (stencilAttachedTo != 0) {
            GL45.glNamedFramebufferTexture(stencilAttachedTo, GL30.GL_STENCIL_ATTACHMENT, 0, 0);
            stencilAttachedTo = 0;
        }
    }

    /** Right before Voxy's composite into the bound framebuffer, which Voxy draws with the stencil test off. */
    public static void limitCompositeToPortal() {
        if (!PortalRendering.isRendering() || !saved || !stencilTest || IrisInterface.invoker.isShaders()) {
            return;
        }
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(func, ref, valueMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glStencilMask(0);
    }

    private static boolean ensureStencilAttached() {
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) {
            return false;
        }
        int stencilType = GL45.glGetNamedFramebufferAttachmentParameteri(
            framebuffer, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );
        if (stencilType != GL11.GL_NONE) {
            // Minecraft's own framebuffer (Roxy fell back to it)
            return true;
        }
        int depthType = GL45.glGetNamedFramebufferAttachmentParameteri(
            framebuffer, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );
        if (depthType != GL11.GL_TEXTURE) {
            return false;
        }
        int depthTexture = GL45.glGetNamedFramebufferAttachmentParameteri(
            framebuffer, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );
        int format = GL45.glGetTextureLevelParameteri(depthTexture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (format != GL30.GL_DEPTH24_STENCIL8 && format != GL30.GL_DEPTH32F_STENCIL8) {
            return false;
        }
        GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_STENCIL_ATTACHMENT, depthTexture, 0);
        if (GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_STENCIL_ATTACHMENT, 0, 0);
            return false;
        }
        stencilAttachedTo = framebuffer;
        return true;
    }

    /**
     * Whether Roxy tracks this render-generation service (its results go through Roxy's stale-result guard). True
     * when Roxy cannot be read, which keeps Roxy's own behaviour.
     */
    public static boolean isRoxyActiveService(Object renderGenerationService) {
        Field field = roxyActiveServiceField();
        if (field == null) {
            return true;
        }
        try {
            return field.get(null) == renderGenerationService;
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static Field roxyActiveServiceField() {
        if (!roxyLookupDone) {
            lookUpRoxy();
        }
        return roxyActiveService;
    }

    private static synchronized void lookUpRoxy() {
        if (!roxyLookupDone) {
            try {
                Field field = Class.forName("net.rasanovum.roxy.patch.RoxyVoxyRenderPatch")
                    .getDeclaredField("activeRenderGenerationService");
                field.setAccessible(true);
                roxyActiveService = field;
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("[IPVoxy] Cannot read Roxy's active Voxy renderer; LODs through portals may stay empty", e);
            }
            roxyLookupDone = true;
        }
    }
}
