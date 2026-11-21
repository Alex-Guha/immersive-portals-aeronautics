/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalPocketCases;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.function.Function;

/**
 * Create: Pocket Sized's entities in a case ({@link IPShipPortalPocketCases}): a portal never goes into one (a world
 * portal in the ship's box would otherwise leave the world with it), and a portal coming out of one packed before is
 * dropped, its leftover portal blocks cleared.
 */
@Mixin(targets = "com.misterblusky9.pocket.pocket.PocketedEntities", remap = false)
public class MixinPocketedEntities_Portals {
    @Inject(method = "isCapturable", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ip_portalsStayOut(final Entity entity, final CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Portal) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = "restore", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityType;loadEntityRecursive(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;Ljava/util/function/Function;)Lnet/minecraft/world/entity/Entity;"),
            require = 0)
    private static Entity ip_dropUnpackedPortals(final CompoundTag tag, final Level level, final Function<Entity, Entity> onLoad,
                                                 final Operation<Entity> original) {
        final Entity entity = original.call(tag, level, onLoad);
        if (entity instanceof final Portal portal) {
            IPShipPortalPocketCases.onUnpackedPortal(portal);
            return null;
        }
        return entity;
    }

    @Inject(method = "restore", at = @At("RETURN"), require = 0)
    private static void ip_clearDroppedPortalBlocks(final ServerLevel level, final ServerSubLevel subLevel, final CompoundTag source,
                                                    final CallbackInfoReturnable<Integer> cir) {
        IPShipPortalPocketCases.afterUnpacking(level, subLevel);
    }
}
