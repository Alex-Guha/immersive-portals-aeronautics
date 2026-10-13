/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.level.ClientLevelModule;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * DH ticks every loaded client level's LOD quadtree around the player's own coordinates (the camera
 * or block position; with IP present, the "actual" ones outside any portal pass). For a dimension
 * the player is only looking into through a portal that area is meaningless, so the position the
 * player is looking at in that dimension ({@link IPDhPortalView}) is used instead. The player's own
 * dimension is untouched.
 */
@Mixin(value = ClientLevelModule.class, remap = false)
public class MixinDhClientLevelModule_PortalViewPosition {
    @Shadow
    @Final
    private IDhClientLevel clientLevel;

    @Redirect(
        method = "clientTick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper;getCameraExactPosition()Lcom/seibel/distanthorizons/core/util/math/DhVec3d;"
        ),
        require = 0
    )
    private DhVec3d ip_cameraPositionForThisLevel(IMinecraftRenderWrapper render) {
        Vec3 view = ip_portalViewPosition();
        return view == null ? render.getCameraExactPosition() : new DhVec3d(view.x, view.y, view.z);
    }

    @Redirect(
        method = "clientTick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftClientWrapper;getPlayerBlockPos()Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;"
        ),
        require = 0
    )
    private DhBlockPos ip_playerPositionForThisLevel(IMinecraftClientWrapper client) {
        Vec3 view = ip_portalViewPosition();
        return view == null ? client.getPlayerBlockPos() : new DhBlockPos((int) Math.floor(view.x), (int) Math.floor(view.y), (int) Math.floor(view.z));
    }

    /** The portal view position for this level's dimension, or null for the player's own dimension / unknown. */
    @Unique
    private @Nullable Vec3 ip_portalViewPosition() {
        if (!IPDhPortalView.ENABLED) {
            return null;
        }
        try {
            IClientLevelWrapper wrapper = this.clientLevel.getClientLevelWrapper();
            if (!(wrapper instanceof ClientLevelWrapper_neoforge mcWrapper)) {
                return null;
            }
            ClientLevel level = mcWrapper.getWrappedMcObject();
            if (level == null) {
                return null;
            }
            ResourceKey<Level> dim = level.dimension();
            if (dim.equals(RenderStates.originalPlayerDimension)) {
                return null;
            }
            return IPDhPortalView.viewPositionIn(dim);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
