/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.q_misc_util.ducks;

import net.minecraft.world.level.storage.LevelStorageSource;

import java.util.concurrent.Executor;

public interface IEMinecraftServer_Misc {
    
    LevelStorageSource.LevelStorageAccess ip_getStorageSource();
    
    // Immersive Aeronautics: for levels added while the server runs (AlternateDimensions)
    Executor ip_getExecutor();
}
