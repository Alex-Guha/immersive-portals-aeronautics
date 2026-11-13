/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.IPPehkuiScaling;
import qouteall.imm_ptl.core.portal.Portal;

@SuppressWarnings({"resource", "JavadocReference", "DanglingJavadoc"})
public class ScaleUtils {
    
    /**
     * It's the id of attribute modifier of scale.
     */
    public static final ResourceLocation IPORTAL_SCALING =
        ResourceLocation.fromNamespaceAndPath("iportal", "scaling");

    // @Nick1st moved to Client class
//    @Environment(EnvType.CLIENT)
//    public static void onClientPlayerTeleported(Portal portal) {
//        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
//            Minecraft client = Minecraft.getInstance();
//
//            LocalPlayer player = client.player;
//
//            Validate.notNull(player, "Player is null");
//
//            doScalingForEntity(player, portal);
//
//            IECamera camera = (IECamera) client.gameRenderer.getMainCamera();
//            camera.ip_setCameraY(
//                ((float) (camera.ip_getCameraY() * portal.getScaling())),
//                ((float) (camera.ip_getLastCameraY() * portal.getScaling()))
//            );
//        }
//    }
    
    public static void onServerEntityTeleported(Entity entity, Portal portal) {
        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
            doScalingForEntity(entity, portal);
            
            if (entity.getVehicle() != null) {
                doScalingForEntity(entity.getVehicle(), portal);
            }
        }
    }
    
    public static @Nullable AttributeInstance getScaleAttr(Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity.getAttributes().getInstance(Attributes.SCALE);
        }
        return null;
    }
    
    public static double getScale(Entity entity) {
        // Immersive Aeronautics: Pehkui's base scale as well, which carries a portal's scaling when Pehkui is installed
        return getVanillaScale(entity) * IPPehkuiScaling.baseScale(entity);
    }
    
    // Immersive Aeronautics: the vanilla scale attribute alone
    private static double getVanillaScale(Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity.getScale();
        }
        return 1.0;
    }
    
    public static double getBaseScale(Entity entity) {
        AttributeInstance scaleAttr = getScaleAttr(entity);
        if (scaleAttr != null) {
            return scaleAttr.getBaseValue();
        }
        return 1.0;
    }
    
    public static double getIPortalScaling(Entity entity) {
        // Immersive Aeronautics: with Pehkui, a portal's scaling is Pehkui's (IPPehkuiScaling); living entities only,
        // as without it
        if (IPPehkuiScaling.active()) {
            return entity instanceof LivingEntity ? IPPehkuiScaling.portalScaling(entity) : 1;
        }
        return getAttributePortalScaling(entity);
    }
    
    // Immersive Aeronautics: the iportal:scaling modifier on the vanilla scale attribute (IP's own getIPortalScaling)
    public static double getAttributePortalScaling(Entity entity) {
        AttributeInstance scaleAttr = getScaleAttr(entity);
        if (scaleAttr == null) {
            return 1;
        }
        
        AttributeModifier modifier = scaleAttr.getModifier(IPORTAL_SCALING);
        if (modifier == null) {
            return 1;
        }
        
        /**
         * {@link AttributeInstance#calculateValue()}
         */
        return modifier.amount() + 1.0;
    }
    
    public static void setIPortalScaling(Entity entity, double newScale) {
        // Immersive Aeronautics: with Pehkui, a portal's scaling is Pehkui's (IPPehkuiScaling); living entities only,
        // as without it
        if (IPPehkuiScaling.active()) {
            if (entity instanceof LivingEntity) {
                IPPehkuiScaling.setPortalScaling(entity, newScale);
            }
            return;
        }
        setAttributePortalScaling(entity, newScale);
    }
    
    // Immersive Aeronautics: the iportal:scaling modifier on the vanilla scale attribute (IP's own setIPortalScaling)
    public static void setAttributePortalScaling(Entity entity, double newScale) {
        AttributeInstance scaleAttr = getScaleAttr(entity);
        if (scaleAttr == null) {
            return;
        }
        
        if (Math.abs(newScale - 1.0) < 0.0001) {
            scaleAttr.removeModifier(IPORTAL_SCALING);
            
            // it updates cached eyeHeight field, which is important in feet pos calculation
            entity.refreshDimensions();
            
            return;
        }
        
        /**
         * {@link AttributeInstance#calculateValue()}
         */
        scaleAttr.addOrReplacePermanentModifier(new AttributeModifier(
            IPORTAL_SCALING,
            newScale - 1.0,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
        ));
        
        // it updates cached eyeHeight field, which is important in feet pos calculation
        entity.refreshDimensions();
    }
    
    public static void setBaseScale(Entity entity, double scale) {
        AttributeInstance scaleAttr = getScaleAttr(entity);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(scale);
            
            // it updates cached eyeHeight field, which is important in feet pos calculation
            entity.refreshDimensions();
        }
    }
    
    // Immersive Aeronautics: the three below multiply in Pehkui's own scale of each (1 without Pehkui), as IP did
    // before it moved to the vanilla attribute, so a player Pehkui scales (a portal, /scale, Create: Pocket Sized) gets
    // the third-person distance and reach through portals Pehkui gives them elsewhere
    public static double computeThirdPersonScale(Entity entity) {
        return getVanillaScale(entity) * IPPehkuiScaling.thirdPersonScale(entity);
    }
    
    public static double computeBlockReachScale(Entity entity) {
        return getVanillaScale(entity) * IPPehkuiScaling.blockReachScale(entity);
    }
    
    public static double computeMotionScale(Entity entity) {
        return getVanillaScale(entity) * IPPehkuiScaling.motionScale(entity);
    }
    
    public static void doScalingForEntity(Entity entity, Portal portal) {
        // Immersive Aeronautics: by the portal's factor (doScalingForEntity(Entity, double))
        doScalingForEntity(entity, portal.getScaling());
    }

    /**
     * Immersive Aeronautics: {@link #doScalingForEntity(Entity, Portal)} by a factor, for an entity our code moves through
     * a portal (or back, 1 / its scaling) without IP's teleport (a player seated on a ship, IPStraddleSeats).
     */
    public static void doScalingForEntity(Entity entity, double factor) {
        Vec3 eyePos = McHelper.getEyePos(entity);
        Vec3 lastTickEyePos = McHelper.getLastTickEyePos(entity);

        double oldScale = ScaleUtils.getIPortalScaling(entity);
        double newScale = transformScale(factor, oldScale);
        
        if (!entity.level().isClientSide && isScaleIllegal(newScale)) {
            newScale = 1;
            entity.sendSystemMessage(
                Component.literal("Scale out of range")
            );
        }
        
        ScaleUtils.setIPortalScaling(entity, newScale);
        
        // Immersive Aeronautics: Pehkui scales fall damage by the entity's size (pehkui:falling, 1 / motion scale),
        // so the distance fallen before the portal, at the old size, is carried over at the new one: 5 blocks above a
        // x0.5 portal are 2.5 on its far side. Unscaled, a pig falling 5 blocks into a x0.5 portal and 2 more counted
        // (5 + 2) x 2 = 14 at its landing, and died
        if (IPPehkuiScaling.active() && entity instanceof LivingEntity) {
            entity.fallDistance = (float) (entity.fallDistance * (newScale / oldScale));
        }
        
        if (!entity.level().isClientSide) {
            McHelper.setEyePos(entity, eyePos, lastTickEyePos);
            McHelper.updateBoundingBox(entity);
        }
        else {
            McHelper.setEyePos(entity, eyePos, lastTickEyePos);
            McHelper.updateBoundingBox(entity);
        }
    }
    
    // Immersive Aeronautics: a factor, not a portal
    private static double transformScale(double factor, double oldScale) {
        double result = (double) (oldScale * factor);
        
        // avoid deviation accumulating
        if (Math.abs(result - 1.0) < 0.0001) {
            result = 1;
        }
        
        return result;
    }
    
    private static boolean isScaleIllegal(double scale) {
        return (scale > IPGlobal.scaleLimit) || (scale < (1.0 / (IPGlobal.scaleLimit * 2)));
    }
    
}
