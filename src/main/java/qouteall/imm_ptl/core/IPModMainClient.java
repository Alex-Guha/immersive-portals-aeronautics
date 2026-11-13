/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core;

import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.miscellaneous.DubiousThings;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.render.context_management.CloudContext;
import qouteall.imm_ptl.core.render.optimization.GLResourceCache;
import qouteall.imm_ptl.core.render.optimization.SharedBlockMeshBuffers;
import qouteall.imm_ptl.core.render.renderer.RendererUsingFrameBuffer;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.dimension.DimensionIntId;
import qouteall.q_misc_util.my_util.MyTaskList;

public class IPModMainClient {
    
    private static void showNvidiaVideoCardWarning() {
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (IPMcHelper.isNvidiaVideocard()) {
                    if (!SodiumInterface.invoker.isSodiumPresent()) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.nvidia_warning")
                                .withStyle(ChatFormatting.RED)
                                .append(McHelper.getLinkText("https://github.com/CaffeineMC/sodium-fabric/issues/1486"))
                        );
                    }
                }
            })
        ));
    }
    
    
    public static void init() {
        // Immersive Aeronautics: client copy of the server's ship straddle sessions (Sable classes only with Sable present)
        if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
            qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.init();
            qouteall.imm_ptl.core.compat.sable_integration.IPShipRenderWatchdog.init();
        }
        // Immersive Aeronautics: is every compat hook of the client's classes alive?
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
            e -> qouteall.imm_ptl.core.compat.IPMixinReport.report());
        // Immersive Aeronautics: a straddling ship's lamps light both sides (Create Dynamic Lights)
        if (net.neoforged.fml.ModList.get().isLoaded("sable") && net.neoforged.fml.ModList.get().isLoaded("sodiumdynamiclights")
            && net.neoforged.fml.ModList.get().isLoaded("createdynlight")) {
            qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleDynLights.init();
        }
        // Immersive Aeronautics: hot-air overlay cut at the straddle plane (Veil shader patch, registered at construction)
        if (net.neoforged.fml.ModList.get().isLoaded("sable") && net.neoforged.fml.ModList.get().isLoaded("aeronautics")) {
            qouteall.imm_ptl.core.compat.sable_straddle.client.IPBalloonOverlayClip.init();
        }
        // Immersive Aeronautics: Distant Horizons' LODs cut away inside the chunks a portal view draws
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
            qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalDrawnChunks.init();
            // Immersive Aeronautics: portals behind DH's LODs hidden (dimension stack floors and ceilings)
            qouteall.imm_ptl.core.compat.dh_integration.IPDhLodOcclusionCapture.init();
            // Immersive Aeronautics: the LOD fog of a distant portal's view, without a shader pack
            qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalFog.init();
        }
        ClientWorldLoader.init();
        
        ClientTeleportationManager.init();
        
        Minecraft.getInstance().execute(() -> {
            ShaderCodeTransformation.init();
            
            MyRenderHelper.init();
            
            IPCGlobal.rendererUsingStencil = new RendererUsingStencil();
            IPCGlobal.rendererUsingFrameBuffer = new RendererUsingFrameBuffer();
            
            IPCGlobal.renderer = IPCGlobal.rendererUsingStencil;
        });
        
        DubiousThings.init();
        
        CrossPortalEntityRenderer.init();
        
        GLResourceCache.init();
        
        CollisionHelper.initClient();
        
        PortalRenderInfo.init();
        
        CloudContext.init();
        
        SharedBlockMeshBuffers.init();
        
        GcMonitor.initClient();

        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> {
            ClientDebugCommand.register(event.getDispatcher());
        });
        
//        showIntelVideoCardWarning();
        
        showNvidiaVideoCardWarning();
        
        
        StableClientTimer.init();
        
        ClientPortalAnimationManagement.init();
        
        VisibleSectionDiscovery.init();
        
        ImmPtlViewArea.init();
        
    
        GuiPortalRendering._init();

        ImmPtlNetworkConfig.initClient();
        
        ForceMainThreadRebuild.init();

        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> {
            IPGlobal.CLIENT_TASK_LIST.forceClearTasks();
        });

        DimensionIntId.initClient();
    }
    
}
