/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.google.common.cache.LoadingCache;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LevelAttached.class, remap = false)
public interface IPLevelAttachedAccessor {
    @Accessor("cache")
    LoadingCache<LevelAccessor, Object> ip_getCache();
}
