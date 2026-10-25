/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.q_misc_util.mixin;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.q_misc_util.MiscGlobals;
import qouteall.q_misc_util.dimension.DimensionIntId;
import qouteall.q_misc_util.ducks.IEMinecraftServer_Misc;

import java.lang.ref.WeakReference;
import java.net.Proxy;
import java.util.concurrent.Executor;

@SuppressWarnings("rawtypes")
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_Misc extends ReentrantBlockableEventLoop implements IEMinecraftServer_Misc {
    public MixinMinecraftServer_Misc(String string) {
        super(string);
        throw new RuntimeException();
    }
    
    @Shadow
    public abstract boolean isDedicatedServer();
    
    @Shadow
    @Final
    public LevelStorageSource.LevelStorageAccess storageSource;
    
    @Shadow
    @Final
    private Executor executor;
    
    @Shadow
    protected abstract void waitUntilNextTick();
    
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onConstruct(
        Thread thread, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem, Proxy proxy, DataFixer dataFixer, Services services, ChunkProgressListenerFactory chunkProgressListenerFactory, CallbackInfo ci
    ) {
        MiscGlobals.refMinecraftServer = new WeakReference<>((MinecraftServer) ((Object) this));
    }
    
    @Inject(method = "createLevels", at = @At("RETURN"))
    private void onWorldsCreated(ChunkProgressListener listener, CallbackInfo ci) {
        DimensionIntId.onServerStarted((MinecraftServer) (Object) this);
    }
    
    @Override
    public LevelStorageSource.LevelStorageAccess ip_getStorageSource() {
        return storageSource;
    }
    
    // Immersive Aeronautics
    @Override
    public Executor ip_getExecutor() {
        return executor;
    }
}
