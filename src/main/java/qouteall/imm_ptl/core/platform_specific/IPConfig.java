/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.platform_specific;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.peripheral.platform_specific.IPFeatureControl;
import qouteall.q_misc_util.Helper;

import java.util.HashSet;

// Immersive Aeronautics: a plain class saved by IPConfigHolder (was Cloth Config's AutoConfig); the options the
// config screen shows, their bounds and tooltips are listed in IPConfigScreen
public class IPConfig {
    // json does not allow comments...
    public String check_the_wiki_for_more_information = "https://qouteall.fun/immptl/wiki/Config-Options";
    
    // client visible configs
    
    public int maxPortalLayer = 5;
    public boolean lagAttackProof = true;
    public boolean enableCrossPortalSound = true;
    public boolean pureMirror = false;
    public boolean renderYourselfInPortal = true;
    public boolean correctCrossPortalEntityRendering = true;
    public boolean reducedPortalRendering = false;
    // Immersive Aeronautics: how far away a dimension stack's floor or ceiling is drawn, in chunks (0: the render
    // distance; more needs Distant Horizons); it fades out over the last quarter (IPGlobalPortalFade)
    public int dimensionStackViewDistance = 0;
    // Immersive Aeronautics: how far past a portal its view shows the other side, in chunks, ending in fog
    // (0: the render distance; IPPortalViewDistance)
    public int portalViewDistance = 0;
    public boolean netherPortalOverlay = false;
    public boolean enableNetherPortalEffect = true;
    public boolean enableClientPerformanceAdjustment = true;
    public boolean clientTolerantVersionMismatchWithServer = false;
    public boolean compatibilityRenderMode = false;
    
    // client invisible configs
    
    public boolean checkModInfoFromInternet = false;
    public boolean enableUpdateNotification = false;
    public boolean sharedBlockMeshBufferOptimization = true;
    public boolean enableClippingMechanism = true;
    public boolean visibilityPrediction = true;
    public boolean useDepthClampForPortalRendering = true;
    public boolean enableCrossPortalView = true;
    public int portalRenderLimit = 200;
    public boolean doCheckGlError = false;
    public boolean shaderpackWarning = true;
    public int portalWandCursorAlignment = 2; // zero for no align
    public boolean saveMemoryInBufferPack = false;
    public boolean initialScreenShown = true;
    
    // common visible configs
    
    public IPGlobal.NetherPortalMode netherPortalMode =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault() ?
            IPGlobal.NetherPortalMode.adaptive : IPGlobal.NetherPortalMode.vanilla;
    
    public IPGlobal.EndPortalMode endPortalMode =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault() ?
            IPGlobal.EndPortalMode.normal : IPGlobal.EndPortalMode.vanilla;
    
    public boolean enableMirrorCreation =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault();
    
    public boolean enableWarning = true;
    public boolean lightVanillaNetherPortalWhenCrouching = true;
    public boolean enableServerPerformanceAdjustment = true;
    public boolean enableDatapackPortalGen = true;
    public int indirectLoadingRadiusCap = 8;
    public int regularPortalLengthLimit = 64;
    public int scaleLimit = 30;
    public boolean easeCreativePermission = true;
    public boolean easeCommandStickPermission = false;
    public boolean portalsChangeGravityByDefault = false;
    public boolean portalWandUsableOnSurvivalMode = false;
    // Immersive Aeronautics: portals on Sable ships (docs/plans/ship-mounted-portals.md, link-strain.md). The mode is
    // null until onConfigChanged settles it: a config with shipMountedPortals keeps what it did, a new one is limited
    public IPGlobal.ShipPortalMode shipPortalMode = null;
    public int shipPortalRangeChunks = 64;
    // read from configs older than shipPortalMode, then dropped
    @Nullable
    public Boolean shipMountedPortals = null;

    // common invisible configs
    
    public int portalSearchingRange = 128;
    public boolean serverSideNormalChunkLoading = true;
    public boolean teleportationDebug = false;
    public boolean looseMovementCheck = false;
    public boolean chunkPacketDebug = false;
    public boolean enableImmPtlChunkLoading = true;
    public boolean serverTolerantVersionMismatchWithClient = false;
    public boolean serverRejectClientWithoutImmPtl = true;
    public boolean serverTeleportLogging = false;
    public boolean enableCrossPortalInteraction = true;
    public HashSet<String> disabledWarnings = new HashSet<>();
    
    @Nullable
    public JsonObject dimStackPreset = null;
    
    // in chunks. A fresh portal may link to a frame up to about 532 overworld blocks from its mapped position: a lower
    // limit could break a portal as it is lit (docs/plans/link-strain.md)
    public static final int SHIP_PORTAL_RANGE_MIN = 38;
    public static final int SHIP_PORTAL_RANGE_MAX = 625;
    
    public static IPConfig getConfig() {
        return IPGlobal.configHolder.getConfig();
    }
    
    public void saveConfigFile() {
        IPGlobal.configHolder.setConfig(this);
        IPGlobal.configHolder.save();
    }
    
    public void onConfigChanged() {
        indirectLoadingRadiusCap = Mth.clamp(indirectLoadingRadiusCap, 1, 32);
        regularPortalLengthLimit = Mth.clamp(regularPortalLengthLimit, 3, 64);
        scaleLimit = Mth.clamp(scaleLimit, 8, 128);
        // Immersive Aeronautics
        portalViewDistance = Mth.clamp(portalViewDistance, 0, 32);
        shipPortalRangeChunks = Mth.clamp(shipPortalRangeChunks, SHIP_PORTAL_RANGE_MIN, SHIP_PORTAL_RANGE_MAX);
        if (shipPortalMode == null) {
            shipPortalMode = shipMountedPortals == null ? IPGlobal.ShipPortalMode.limited
                : shipMountedPortals ? IPGlobal.ShipPortalMode.unlimited : IPGlobal.ShipPortalMode.off;
        }
        shipMountedPortals = null;
        if (netherPortalMode == null) {
            netherPortalMode = IPGlobal.NetherPortalMode.adaptive;
        }
        if (endPortalMode == null) {
            endPortalMode = IPGlobal.EndPortalMode.normal;
        }
        
        IPGlobal.renderMode = compatibilityRenderMode ? IPGlobal.RenderMode.compatibility : IPGlobal.RenderMode.normal;
        IPGlobal.enableWarning = enableWarning;
        IPGlobal.enableMirrorCreation = enableMirrorCreation;
        IPGlobal.doCheckGlError = doCheckGlError;
        IPGlobal.maxPortalLayer = maxPortalLayer;
        IPGlobal.lagAttackProof = lagAttackProof;
        IPGlobal.portalRenderLimit = portalRenderLimit;
        IPGlobal.netherPortalFindingRadius = portalSearchingRange;
        IPGlobal.renderYourselfInPortal = renderYourselfInPortal;
        IPGlobal.activeLoading = serverSideNormalChunkLoading;
        IPGlobal.teleportationDebugEnabled = teleportationDebug;
        IPGlobal.correctCrossPortalEntityRendering = correctCrossPortalEntityRendering;
        IPGlobal.looseMovementCheck = looseMovementCheck;
        IPGlobal.pureMirror = pureMirror;
        IPGlobal.indirectLoadingRadiusCap = indirectLoadingRadiusCap;
        IPGlobal.netherPortalMode = netherPortalMode;
        IPGlobal.endPortalMode = endPortalMode;
        IPGlobal.reducedPortalRendering = reducedPortalRendering;
        IPGlobal.offsetOcclusionQuery = visibilityPrediction;
        IPGlobal.netherPortalOverlay = netherPortalOverlay;
        IPGlobal.scaleLimit = scaleLimit;
        IPGlobal.easeCreativePermission = easeCreativePermission;
        IPGlobal.easeCommandStickPermission = easeCommandStickPermission;
        IPGlobal.enableSharedBlockMeshBuffers = sharedBlockMeshBufferOptimization;
        IPGlobal.enableDatapackPortalGen = enableDatapackPortalGen;
        IPGlobal.enableCrossPortalView = enableCrossPortalView;
        IPGlobal.enableClippingMechanism = enableClippingMechanism;
        IPGlobal.lightVanillaNetherPortalWhenCrouching = lightVanillaNetherPortalWhenCrouching;
        IPGlobal.enableNetherPortalEffect = enableNetherPortalEffect;
        IPGlobal.enableClientPerformanceAdjustment = enableClientPerformanceAdjustment;
        IPGlobal.enableServerPerformanceAdjustment = enableServerPerformanceAdjustment;
        IPGlobal.enableCrossPortalSound = enableCrossPortalSound;
        IPGlobal.checkModInfoFromInternet = checkModInfoFromInternet;
        IPGlobal.enableUpdateNotification = enableUpdateNotification;
        IPGlobal.enableDepthClampForPortalRendering = useDepthClampForPortalRendering;
        BlockPortalShape.defaultLengthLimit = regularPortalLengthLimit;
        IPGlobal.maxNormalPortalRadius = Math.max(regularPortalLengthLimit / 2, 16);
        IPGlobal.chunkPacketDebug = chunkPacketDebug;
        IPGlobal.saveMemoryInBufferPack = saveMemoryInBufferPack;
        
        Helper.LOGGER.info("iPortal Config Applied");
    }
    
    public boolean shouldDisplayWarning(String warningKey) {
        return enableWarning && !disabledWarnings.contains(warningKey);
    }
    
}
