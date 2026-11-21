/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;

import static qouteall.imm_ptl.core.ScaleUtils.doScalingForEntity;

// @Nick1st copy of ScaleUtils with ClientOnly code
public class ScaleUtilsClient {
    public static void onClientPlayerTeleported(Portal portal) {
        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
            Minecraft client = Minecraft.getInstance();

            LocalPlayer player = client.player;

            Validate.notNull(player, "Player is null");

            // Immersive Aeronautics: the velocity IP's teleport just transformed keeps the portal's scaling once with
            // Pehkui, which scales the player's moves by their new size as well (ScaleUtils.onTeleportedWithVelocity)
            double movementScaleBefore = qouteall.imm_ptl.core.compat.IPPehkuiScaling.movementScale(player);
            doScalingForEntity(player, portal);
            ScaleUtils.keepWorldVelocity(player, movementScaleBefore);

            IECamera camera = (IECamera) client.gameRenderer.getMainCamera();
            camera.ip_setCameraY(
                    ((float) (camera.ip_getCameraY() * portal.getScaling())),
                    ((float) (camera.ip_getLastCameraY() * portal.getScaling()))
            );
        }
    }
}
