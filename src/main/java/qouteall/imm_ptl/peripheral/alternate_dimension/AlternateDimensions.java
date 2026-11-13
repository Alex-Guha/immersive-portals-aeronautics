/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackEntry;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackInfo;
import qouteall.imm_ptl.peripheral.dim_stack.DimensionStackAPI;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.ducks.IEMinecraftServer_Misc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

// Immersive Aeronautics: the alternate dimensions (skyland, chaos, void and their fully bright variants), the spacers
// of a dimension stack, are back. Upstream made them through DimLib, which the NeoForge port dropped, and left them
// commented out. Here a level is added while the server runs, as Infinite Dimensions does (MinecraftServer's level
// map plus markWorldsDirty, then LevelEvent.Load, on which DimensionIntId tells the clients), and kept in
// AlternateDimensionStorage so that it comes back on every start. Its level stem is not registered, so it never reaches
// level.dat and the world still loads without this mod. Copies (immersive_portals:void_2, void_3, ...) let a stack
// hold the same kind of spacer more than once.
public class AlternateDimensions {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceKey<DimensionType> SURFACE_TYPE = ResourceKey.create(
        Registries.DIMENSION_TYPE,
        McHelper.newResourceLocation("immersive_portals:surface_type")
    );

    public static final ResourceKey<DimensionType> SURFACE_TYPE_BRIGHT = ResourceKey.create(
        Registries.DIMENSION_TYPE,
        McHelper.newResourceLocation("immersive_portals:surface_type_bright")
    );

    public static final ResourceKey<Level> SKYLAND = ResourceKey.create(
        Registries.DIMENSION,
        McHelper.newResourceLocation("immersive_portals:skyland")
    );

    public static final ResourceKey<Level> BRIGHT_SKYLAND = ResourceKey.create(
        Registries.DIMENSION,
        McHelper.newResourceLocation("immersive_portals:bright_skyland")
    );

    public static final ResourceKey<Level> CHAOS = ResourceKey.create(
        Registries.DIMENSION,
        McHelper.newResourceLocation("immersive_portals:chaos")
    );

    public static final ResourceKey<Level> VOID = ResourceKey.create(
        Registries.DIMENSION,
        McHelper.newResourceLocation("immersive_portals:void")
    );

    public static final ResourceKey<Level> BRIGHT_VOID = ResourceKey.create(
        Registries.DIMENSION,
        McHelper.newResourceLocation("immersive_portals:bright_void")
    );

    private record Template(
        ResourceKey<DimensionType> dimensionType,
        BiFunction<RegistryAccess, Long, ChunkGenerator> generator
    ) {}

    private static final Map<ResourceKey<Level>, Template> TEMPLATES = Map.of(
        BRIGHT_SKYLAND, new Template(SURFACE_TYPE_BRIGHT, AlternateDimensions::createSkylandGenerator),
        SKYLAND, new Template(SURFACE_TYPE, AlternateDimensions::createSkylandGenerator),
        CHAOS, new Template(SURFACE_TYPE, (rm, seed) -> createErrorTerrainGenerator(seed, rm)),
        VOID, new Template(SURFACE_TYPE, (rm, seed) -> createVoidGenerator(rm)),
        BRIGHT_VOID, new Template(SURFACE_TYPE_BRIGHT, (rm, seed) -> createVoidGenerator(rm))
    );

    // in the order the dimension stack screen lists them
    private static final List<ResourceKey<Level>> CANDIDATES =
        List.of(BRIGHT_SKYLAND, SKYLAND, CHAOS, VOID, BRIGHT_VOID);

    public static void init() {
        NeoForge.EVENT_BUS.addListener(
            DimensionStackAPI.DimensionStackCandidateCollectionEvent.class,
            e -> e.addToResult(CANDIDATES)
        );

        NeoForge.EVENT_BUS.addListener(
            DimensionStackAPI.DimensionStackPreUpdateEvent.class,
            e -> addAltDimsIfUsedInDimStack(e.server, e.dimStackInfo)
        );

        NeoForge.EVENT_BUS.addListener(
            ServerTickEvent.Post.class,
            e -> tick(e.getServer())
        );
    }

    /**
     * The alternate dimension a dimension id is made from: itself for the five built-in ones, the original for a
     * copy (immersive_portals:void_2 -> immersive_portals:void). Null for any other dimension.
     */
    @Nullable
    public static ResourceKey<Level> getTemplateDimension(ResourceKey<Level> dimension) {
        ResourceLocation id = dimension.location();
        if (!id.getNamespace().equals("immersive_portals")) {
            return null;
        }

        ResourceKey<Level> base = Helper.dimIdToKey(
            McHelper.newResourceLocation("immersive_portals", id.getPath().replaceFirst("_[0-9]+$", ""))
        );

        return TEMPLATES.containsKey(base) ? base : null;
    }

    /**
     * For the dimension stack screen: adding an alternate dimension that the stack already has gives its next free
     * copy, so that a stack can have several spacers of one kind. Any other dimension is returned as it is.
     */
    public static ResourceKey<Level> getDimensionForNewStackEntry(
        ResourceKey<Level> dimension, List<DimStackEntry> entries
    ) {
        ResourceKey<Level> base = getTemplateDimension(dimension);
        if (base == null) {
            return dimension;
        }

        List<String> used = entries.stream().map(e -> e.dimensionIdStr).toList();
        if (!used.contains(dimension.location().toString())) {
            return dimension;
        }

        for (int i = 2; ; i++) {
            ResourceKey<Level> copy = Helper.dimIdToKey(McHelper.newResourceLocation(
                base.location().getNamespace(), base.location().getPath() + "_" + i
            ));
            if (!used.contains(copy.location().toString())) {
                return copy;
            }
        }
    }

    // at the end of MinecraftServer.createLevels, before the dimension stack is applied
    public static void onServerCreatedWorlds(MinecraftServer server) {
        AlternateDimensionStorage storage = AlternateDimensionStorage.get(server);
        for (String idStr : List.copyOf(storage.dimensionIds)) {
            ResourceKey<Level> dimension = Helper.dimIdToKey(idStr);
            if (getTemplateDimension(dimension) == null) {
                LOGGER.warn("Unknown alternate dimension {} in the save, skipped", idStr);
                continue;
            }
            addDimensionIfNotExists(server, dimension);
        }
    }

    private static void addAltDimsIfUsedInDimStack(
        MinecraftServer server, @Nullable DimStackInfo dimStackInfo
    ) {
        if (dimStackInfo == null) {
            return;
        }

        for (DimStackEntry entry : dimStackInfo.entries) {
            ResourceKey<Level> dimension = entry.getDimension();
            if (getTemplateDimension(dimension) != null) {
                addDimensionIfNotExists(server, dimension);
            }
        }
    }

    public static void addDimensionIfNotExists(MinecraftServer server, ResourceKey<Level> dimension) {
        if (server.getLevel(dimension) != null) {
            return;
        }

        ResourceKey<Level> base = getTemplateDimension(dimension);
        if (base == null) {
            LOGGER.error("{} is not an alternate dimension", dimension.location());
            return;
        }
        Template template = TEMPLATES.get(base);

        WorldData worldData = server.getWorldData();
        long seed = worldData.worldGenOptions().seed();
        RegistryAccess.Frozen registryAccess = server.registryAccess();
        Holder<DimensionType> dimensionType =
            registryAccess.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(template.dimensionType);
        LevelStem levelStem = new LevelStem(dimensionType, template.generator.apply(registryAccess, seed));

        // as MinecraftServer.createLevels makes the non-overworld levels
        ServerLevel overworld = server.overworld();
        ServerLevel level = new ServerLevel(
            server,
            ((IEMinecraftServer_Misc) server).ip_getExecutor(),
            ((IEMinecraftServer_Misc) server).ip_getStorageSource(),
            new DerivedLevelData(worldData, worldData.overworldData()),
            dimension,
            levelStem,
            NO_PROGRESS_LISTENER,
            worldData.isDebugWorld(),
            BiomeManager.obfuscateSeed(seed),
            ImmutableList.of(),
            false,
            overworld.getRandomSequences()
        );
        overworld.getWorldBorder().addListener(
            new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder())
        );

        server.forgeGetWorldMap().put(dimension, level);
        server.markWorldsDirty();

        AlternateDimensionStorage storage = AlternateDimensionStorage.get(server);
        if (!storage.dimensionIds.contains(dimension.location().toString())) {
            storage.dimensionIds.add(dimension.location().toString());
            storage.setDirty();
        }

        LOGGER.info("Added alternate dimension {}", dimension.location());

        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    private static final ChunkProgressListener NO_PROGRESS_LISTENER = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {}

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    };

    public static boolean isAlternateDimension(Level world) {
        // by id, not by key identity
        ResourceKey<DimensionType> dimensionTypeId = world.dimensionTypeRegistration().unwrapKey().orElse(null);
        return dimensionTypeId != null && (
            dimensionTypeId.location().equals(SURFACE_TYPE.location())
                || dimensionTypeId.location().equals(SURFACE_TYPE_BRIGHT.location())
        );
    }

    public static ChunkGenerator createSkylandGenerator(RegistryAccess rm, long seed) {
        return NormalSkylandGenerator.create(
            rm.registryOrThrow(Registries.BIOME).asLookup(),
            rm.registryOrThrow(Registries.DENSITY_FUNCTION).asLookup(),
            rm.registryOrThrow(Registries.NOISE).asLookup(),
            rm.registryOrThrow(Registries.NOISE_SETTINGS).asLookup(),
            rm.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).asLookup(),
            seed
        );
    }

    public static ChunkGenerator createErrorTerrainGenerator(long seed, RegistryAccess rm) {
        return ErrorTerrainGenerator.create(
            rm.registryOrThrow(Registries.BIOME).asLookup(),
            rm.registryOrThrow(Registries.NOISE_SETTINGS).asLookup()
        );
    }

    public static ChunkGenerator createVoidGenerator(RegistryAccess rm) {
        Registry<Biome> biomeRegistry = rm.registryOrThrow(Registries.BIOME);

        Holder.Reference<Biome> plainsHolder = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);

        FlatLevelGeneratorSettings flatChunkGeneratorConfig =
            new FlatLevelGeneratorSettings(
                Optional.of(HolderSet.direct()),
                plainsHolder,
                List.of()
            );
        flatChunkGeneratorConfig.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
        flatChunkGeneratorConfig.updateLayers();

        return new FlatLevelSource(flatChunkGeneratorConfig);
    }

    private static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            if (isAlternateDimension(world)) {
                syncWeatherFromOverworld(world, overworld);
            }
        }
    }

    private static void syncWeatherFromOverworld(
        ServerLevel world, ServerLevel overworld
    ) {
        ((IEWorld) world).portal_setWeather(
            overworld.getRainLevel(1), overworld.getRainLevel(1),
            overworld.getThunderLevel(1), overworld.getThunderLevel(1)
        );
    }
}
