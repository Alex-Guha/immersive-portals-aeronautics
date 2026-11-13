/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Create: Pocket Sized (Pym) keeps each ship's client scale in a map by UUID, and every client tick its
 * {@code ScaleHandshake} lists the ships of the player's own level ({@code Minecraft.level}): a ship it has asked about
 * that is not in that list is forgotten ({@code forgetClientSnapshot}), and a ship without a scale is asked for. A
 * forgotten ship reads scale 1, which Pym writes into the ship's pose every client tick ({@code enforceClientScale}
 * from {@code ClientSubLevel.tick}). So a scaled ship in another dimension, seen through a portal or as the far half of
 * a straddle, was drawn, picked and collided with at 1x. Here the list is the ships of every client level we hold.
 * The server answers for a ship in any dimension ({@link MixinPymScaleNetwork_PortalViewers}).
 */
@Mixin(targets = "com.misterblusky9.pym.internal.client.ScaleHandshake", remap = false)
public class MixinPymScaleHandshake_AllLevels {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getAllSubLevels()Ljava/util/List;"),
            require = 0)
    private static List<? extends SubLevel> ip_shipsOfEveryLevel(final SubLevelContainer container,
                                                                 final Operation<List<? extends SubLevel>> original) {
        final List<? extends SubLevel> own = original.call(container);
        final List<SubLevel> all = new ArrayList<>(own);
        for (final ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            final ClientSubLevelContainer other = SubLevelContainer.getContainer(level);
            if (other == null || other == container) {
                continue;
            }
            all.addAll(other.getAllSubLevels());
        }
        return all;
    }
}
