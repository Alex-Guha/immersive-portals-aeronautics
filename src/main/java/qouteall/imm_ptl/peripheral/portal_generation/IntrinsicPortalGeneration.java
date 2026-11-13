/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.portal_generation;

import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration;

import java.util.ArrayList;
import java.util.Optional;

public class IntrinsicPortalGeneration {
    public static final IntrinsicNetherPortalForm intrinsicNetherPortalForm =
        new IntrinsicNetherPortalForm();
    public static final DiligentNetherPortalForm diligentNetherPortalForm =
        new DiligentNetherPortalForm();
    public static final PortalHelperForm portalHelperForm =
        new PortalHelperForm();
    
    public static final CustomPortalGeneration intrinsicToNether = new CustomPortalGeneration(
        Lists.newArrayList(Level.OVERWORLD), Level.NETHER,
        8, 1,
        true,
        intrinsicNetherPortalForm,
        null, new ArrayList<>(), new ArrayList<>()
    );
    
    public static final CustomPortalGeneration intrinsicFromNether = intrinsicToNether.getReverse();
    
    public static final CustomPortalGeneration diligentToNether = new CustomPortalGeneration(
        Lists.newArrayList(Level.OVERWORLD), Level.NETHER,
        8, 1,
        true,
        diligentNetherPortalForm,
        null, new ArrayList<>(), new ArrayList<>()
    );
    
    public static final CustomPortalGeneration diligentFromNether = diligentToNether.getReverse();
    
    public static final CustomPortalGeneration portalHelper = new CustomPortalGeneration(
        Lists.newArrayList(CustomPortalGeneration.ANY_DIMENSION),
        CustomPortalGeneration.THE_SAME_DIMENSION,
        1, 1,
        false, portalHelperForm,
        null, new ArrayList<>(), new ArrayList<>()
    );
    
    public static void init() {
        intrinsicToNether.identifier = McHelper.newResourceLocation("imm_ptl:intrinsic_nether_portal");
        intrinsicFromNether.identifier = McHelper.newResourceLocation("imm_ptl:intrinsic_nether_portal");
        
        diligentFromNether.identifier = McHelper.newResourceLocation("imm_ptl:intrinsic_diligent_nether_portal");
        diligentToNether.identifier = McHelper.newResourceLocation("imm_ptl:intrinsic_diligent_nether_portal");
        
        portalHelper.identifier = McHelper.newResourceLocation("imm_ptl:intrinsic_portal_helper");
    }
    
    /**
     * There are two ways of creating nether portal:
     * 1. the player ignites on obsidian (using flint and steel or other items)
     * 2. the fire spreads to obsidian
     */
    public static boolean onFireLitOnObsidian(
        ServerLevel fromWorld,
        BlockPos firePos,
        @Nullable Entity triggeringEntity
    ) {
        IPGlobal.NetherPortalMode mode = IPGlobal.netherPortalMode;
        
        if (mode == IPGlobal.NetherPortalMode.normal || mode == IPGlobal.NetherPortalMode.adaptive) {
            ResourceKey<Level> fromDimension = fromWorld.dimension();
            
            if (fromDimension == Level.OVERWORLD) {
                CustomPortalGeneration gen =
                    mode == IPGlobal.NetherPortalMode.normal ?
                        IntrinsicPortalGeneration.intrinsicToNether :
                        diligentToNether;
                return gen.perform(fromWorld, firePos, triggeringEntity);
            }
            else if (fromDimension == Level.NETHER) {
                CustomPortalGeneration gen =
                    mode == IPGlobal.NetherPortalMode.normal ?
                        IntrinsicPortalGeneration.intrinsicFromNether :
                        diligentFromNether;
                return gen.perform(fromWorld, firePos, triggeringEntity);
            }
        }
        
        return false;
    }
    
    public static boolean activatePortalHelper(
        ServerLevel fromWorld,
        BlockPos firePos
    ) {
        return portalHelper.perform(fromWorld, firePos, null);
    }
    
    public static boolean onCrouchingPlayerIgnite(
        ServerLevel world,
        ServerPlayer player,
        BlockPos firePos
    ) {
        if (IPGlobal.netherPortalMode == IPGlobal.NetherPortalMode.disabled) {
            return false;
        }
        
        if (!IPGlobal.lightVanillaNetherPortalWhenCrouching) {
            return false;
        }
        
        boolean dimensionCorrect =
            world.dimension() == Level.OVERWORLD || world.dimension() == Level.NETHER;
        
        if (!dimensionCorrect) {
            return false;
        }

        // Immersive Aeronautics: no portal from a Sable ship's plot coordinates
        if (!qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals.mayGenerateFrom(world, firePos, null)) {
            return false;
        }

        Optional<PortalShape> newPortal = PortalShape.findEmptyPortalShape(world, firePos, Direction.Axis.X);
        
        if (newPortal.isEmpty()) {
            return false;
        }
        
        PortalShape areaHelper = newPortal.get();
        // Immersive Aeronautics: this vanilla portal is wanted; let it past MixinPortalShape_IntrinsicPortal
        lightingVanillaPortal = true;
        try {
            areaHelper.createPortalBlocks();
        }
        finally {
            lightingVanillaPortal = false;
        }
        return true;
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Immersive Aeronautics: set while onCrouchingPlayerIgnite builds a vanilla portal on purpose
    private static boolean lightingVanillaPortal = false;

    /**
     * Immersive Aeronautics: another mod asked vanilla's {@link PortalShape} to fill a frame with portal blocks
     * (Netherstorm lights frames from its own flint-and-steel handler, before the fire that IP listens to is placed).
     * Unless IP is in vanilla mode, light IP's portal there instead, as if fire had been lit inside the frame.
     *
     * @return true if the vanilla portal blocks must not be placed
     */
    public static boolean onVanillaPortalBlocksRequested(ServerLevel world, BlockPos insidePos) {
        if (lightingVanillaPortal || IPGlobal.netherPortalMode == IPGlobal.NetherPortalMode.vanilla) {
            return false;
        }
        boolean lit = onFireLitOnObsidian(world, insidePos, null);
        LOGGER.info(
            "Vanilla portal blocks requested at {} {}; lit an immersive portal instead: {}",
            world.dimension().location(), insidePos.toShortString(), lit
        );
        return true;
    }
}
