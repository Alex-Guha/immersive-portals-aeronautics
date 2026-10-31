/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.platform_specific;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.compat.IPFoliageCompat;
import qouteall.imm_ptl.core.mixin.common.miscellaneous.IEPortalShape;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.q_misc_util.Helper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class O_O {
    public static boolean isDimensionalThreadingPresent = false;
    
    public static boolean isForge() {
        return false;
    }
    
    //@OnlyIn(Dist.CLIENT)
    public static void onPlayerChangeDimensionClient(
        ResourceKey<Level> from, ResourceKey<Level> to
    ) {
        RequiemCompatClient.onPlayerTeleportedClient();
        // Immersive Aeronautics: Interactive Foliage follows the player's world
        IPFoliageCompat.onPlayerChangeDimensionClient(from);
    }
    
    public static void onPlayerTravelOnServer(
        ServerPlayer player,
        ServerLevel fromWorld, ServerLevel toWorld
    ) {
        RequiemCompat.onPlayerTeleportedServer(player);
    }
    
    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }
    
    private static final BlockState obsidianState = Blocks.OBSIDIAN.defaultBlockState();
    
    public static boolean isObsidian(BlockState blockState) {
        // Immersive Aeronautics: also accept the frame blocks other mods add to nether portals (vanilla's frame
        // test, which Netherstorm extends to its polished obsidian); the test cannot see the block's position here
        return blockState == obsidianState
            || IEPortalShape.ip_getFrame().test(blockState, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
    
    public static void postClientChunkLoadEvent(LevelChunk chunk) {
        // Immersive Aeronautics: newChunk is false on the client, as NeoForge's own client chunk load posts it; mods
        // take true to mean a server-side chunk (Cobbleloots casts the level to ServerLevel)
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
//        ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(
//            ((ClientLevel) chunk.getLevel()), chunk
//        );
    }
    
    public static void postClientChunkUnloadEvent(LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
//        ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(
//            ((ClientLevel) chunk.getLevel()), chunk
//        );
    }
    
    public static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }
    
    public static void postPortalSpawnEventForge(PortalGenInfo info) {
    
    }

    // @Nick1st - Moved to client class
//    @OnlyIn(Dist.CLIENT)
//    public static ClientChunkCache createMyClientChunkManager(ClientLevel world, int loadDistance) {
//        return new ImmPtlClientChunkMap(world, loadDistance);
//    }
    
    public static boolean getIsPehkuiPresent() {
        return ModList.get().isLoaded("pehkui");
    }
    
    @Nullable
    public static String getImmPtlModInfoUrl() {
        // Immersive Aeronautics: upstream's mod info (qouteall.fun/immptl_info) describes upstream's mod: its versions,
        // its incompatible mods and shader packs (Complementary, which works here). Not ours to show; not fetched.
        return null;
    }
    
    public static boolean isModLoadedWithinVersion(String modId, @Nullable String startVersion, @Nullable String endVersion) {
        Optional<? extends ModContainer> modContainer = ModList.get().getModContainerById(modId);
        if (modContainer.isPresent()) {
            ArtifactVersion version = modContainer.get().getModInfo().getVersion();
            
            try {
                if (startVersion != null) {
                    int i = new DefaultArtifactVersion(startVersion).compareTo(version);
                    if (i > 0) {
                        return false;
                    }
                }
                
                if (endVersion != null) {
                    int i = new DefaultArtifactVersion(endVersion).compareTo(version);
                    if (i < 0) {
                        return false;
                    }
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            
            return true;
            
        }
        else {
            return false;
        }
    }
    
    // Immersive Aeronautics: our own version, the first three numbers of it for a test jar ("2.7.8.1-branch"); a dev run
    // has no number
    public static @NotNull ImmPtlNetworkConfig.ModVersion getImmPtlVersion() {
        Matcher version = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(getImmPtlVersionStr());
        if (!version.find()) {
            return ImmPtlNetworkConfig.ModVersion.OTHER;
        }
        return new ImmPtlNetworkConfig.ModVersion(
            Integer.parseInt(version.group(1)),
            Integer.parseInt(version.group(2)),
            Integer.parseInt(version.group(3))
        );
    }
    
    public static String getImmPtlVersionStr() {
        return ModList.get().getModContainerById("ip_aeronautics")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("");
    }
    
    public static boolean shouldUpdateImmPtl(String latestReleaseVersion) {
        return false;
    }
    
    public static String getModDownloadLink() {
        return "https://modrinth.com/mod/immersiveportals";
    }
    
    public static String getIssueLink() {
        return "https://github.com/iPortalTeam/ImmersivePortalsMod/discussions";
    }
    
    @Nullable
    public static ResourceLocation getModIconLocation(String modid) {
        return null;
    }
    
    @Nullable
    public static String getModName(String modid) {
        return FMLLoader.getLoadingModList().getModFileById(modid).getMods().stream().findFirst().get().getDisplayName();
    }
    
    // most quilt installations use quilted fabric api
    public static boolean isQuilt() {
        return false;
    }
    
    public static List<String> getLoadedModIds() {
        return ModList.get().getSortedMods().stream().map(ModContainer::getModId).sorted().toList();
    }

    public static boolean allowTeleportingEntity(Entity entity, Portal portal) {
        // ForgeHooks.onTravelToDimension() on Forge
        return true;
    }
    
    public static boolean isDevEnv() {
        return !FMLEnvironment.production;
    }
}
