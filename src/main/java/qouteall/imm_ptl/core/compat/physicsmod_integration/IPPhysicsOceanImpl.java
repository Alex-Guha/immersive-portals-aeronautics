/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.physicsmod_integration;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.diebuddies.compat.Iris;
import net.diebuddies.minecraft.LevelRendererAccessor;
import net.diebuddies.physics.PhysicsMod;
import net.diebuddies.physics.PhysicsWorld;
import net.diebuddies.physics.StarterClient;
import net.diebuddies.physics.ocean.Index;
import net.diebuddies.physics.ocean.OceanChunk;
import net.diebuddies.physics.ocean.OceanProcessor;
import net.diebuddies.physics.ocean.OceanWorld;
import net.diebuddies.physics.ocean.thread.OceanChunkCreator;
import net.diebuddies.render.MainRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Physics Mod ocean/wave bookkeeping for the main (non-portal) view. Subscribes to
 * {@code RenderLevelStageEvent}; on each main-view pass it clears {@code StarterClient.immersivePortals},
 * re-registers ocean chunk columns around the player per dimension (plus empty boundary columns),
 * repairs neighbour counts and queues re-processing through Physics Mod's private
 * {@code OceanWorld.processor} / {@code OceanProcessor.processChunkColumns} (see {@link PhysicsModInternals}),
 * caches the per-dimension Iris ocean shader so it can be restored after portal passes, and manually
 * issues Physics Mod's cutout/translucent render calls (which {@code MixinPhysicsModMainRenderer}
 * cancels while a portal is being rendered).
 */
public final class IPPhysicsOceanImpl {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean registered = false;
    private static int renderErrorCount = 0;
    private static int scanTick = 0;
    private static final WeakHashMap<OceanWorld, LongOpenHashSet> registrations = new WeakHashMap<>();
    private static final WeakHashMap<OceanWorld, LongOpenHashSet> forcedColumns = new WeakHashMap<>();
    private static final WeakHashMap<OceanWorld, LongOpenHashSet> syntheticColumns = new WeakHashMap<>();
    private static final int MIN_SCAN_RADIUS = 10;
    private static final int MAX_SCAN_RADIUS = 32;
    private static final HashMap<ResourceKey<Level>, ShaderInstance> mainOceanShader = new HashMap<>();
    private static boolean portalPassSincePresent = false;

    private static int scanRadius(Minecraft mc) {
        int radius = MIN_SCAN_RADIUS;

        try {
            radius = mc.options.getEffectiveRenderDistance() + 4;
        } catch (Throwable ignored) {
        }

        return Math.max(MIN_SCAN_RADIUS, Math.min(MAX_SCAN_RADIUS, radius));
    }

    private IPPhysicsOceanImpl() {
    }

    private static void keepOceanShaderForThisDimension(ClientLevel level) {
        try {
            if (!StarterClient.iris) {
                return;
            }

            ResourceKey<Level> dimension = level.dimension();
            // Physics Mod 3.0.34: the ocean shader is owned by the Iris pipeline
            // (Iris.getOceanProgram()); the fork read/wrote the static Iris.oceanShader field,
            // which no longer exists, so the "restore" branch can only be observed, not applied.
            ShaderInstance current = Iris.getOceanProgram();
            ShaderInstance cached = mainOceanShader.get(dimension);
            if (current != cached) {
                if (portalPassSincePresent && cached != null) {
                    // restore branch (see above): the cached shader is deliberately kept
                } else if (current != null) {
                    mainOceanShader.put(dimension, current);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            portalPassSincePresent = false;
        }
    }

    static void register() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, IPPhysicsOceanImpl::onStage);
            LOGGER.debug("[IPPhysicsOcean] Physics Mod detected - ocean/wave integration active");
        }
    }

    private static void onStage(RenderLevelStageEvent event) {
        try {
            if (PortalRendering.isRendering()) {
                portalPassSincePresent = true;
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return;
            }

            LevelRenderer levelRenderer = mc.levelRenderer;
            if (!(levelRenderer instanceof LevelRendererAccessor)) {
                return;
            }

            MainRenderer mainRenderer = ((LevelRendererAccessor) levelRenderer).getMainRenderer();
            StarterClient.immersivePortals = false;
            Vec3 camPos = event.getCamera().getPosition();
            Matrix4f modelView = event.getModelViewMatrix();
            Matrix4f projection = event.getProjectionMatrix();
            Stage stage = event.getStage();
            if (stage == Stage.AFTER_CUTOUT_BLOCKS) {
                if (scanTick++ % 20 == 0) {
                    int changed = scanOceanChunks(level);
                    if (changed > 0) {
                        repairAndSchedule(level);
                    } else {
                        watchdog(level);
                    }
                }

                mainRenderer.renderAll(level, RenderType.cutout(), camPos.x, camPos.y, camPos.z, modelView, projection);
            } else if (stage == Stage.AFTER_TRANSLUCENT_BLOCKS) {
                keepOceanShaderForThisDimension(level);
                mainRenderer.renderLiquid(level, RenderType.translucent(), camPos.x, camPos.y, camPos.z, modelView, projection);
                mainRenderer.renderCloth(level, RenderType.translucent(), camPos.x, camPos.y, camPos.z, modelView, projection);
            }
        } catch (Throwable e) {
            if (renderErrorCount++ % 600 == 0) {
                LOGGER.debug("[IPPhysicsOcean] render error: {}", String.valueOf(e));
            }
        }
    }

    private static OceanWorld oceanWorldOf(ClientLevel level) {
        try {
            PhysicsWorld physicsWorld = PhysicsMod.getInstance(level).getPhysicsWorld();
            return physicsWorld.getOceanWorld();
        } catch (Throwable e) {
            return null;
        }
    }

    private static LongOpenHashSet registeredFor(OceanWorld oceanWorld) {
        return registrations.computeIfAbsent(oceanWorld, w -> new LongOpenHashSet());
    }

    private static LongOpenHashSet forcedFor(OceanWorld oceanWorld) {
        return forcedColumns.computeIfAbsent(oceanWorld, w -> new LongOpenHashSet());
    }

    private static LongOpenHashSet syntheticFor(OceanWorld oceanWorld) {
        return syntheticColumns.computeIfAbsent(oceanWorld, w -> new LongOpenHashSet());
    }

    private static int scanOceanChunks(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }

        OceanWorld oceanWorld = oceanWorldOf(level);
        if (oceanWorld == null) {
            return 0;
        }

        LongOpenHashSet registered = registeredFor(oceanWorld);
        LongOpenHashSet forced = forcedFor(oceanWorld);
        LongOpenHashSet synthetic = syntheticFor(oceanWorld);
        int centerX = mc.player.chunkPosition().x;
        int centerZ = mc.player.chunkPosition().z;
        int radius = scanRadius(mc);
        int removed = 0;

        // drop columns that drifted out of range
        for (long key : registered.toLongArray()) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (Math.abs(cx - centerX) > radius + 3 || Math.abs(cz - centerZ) > radius + 3) {
                try {
                    oceanWorld.removeChunkColumn(cx, cz);
                } catch (Throwable ignored) {
                }

                registered.remove(key);
                synthetic.remove(key);
                invalidateAround(forced, cx, cz);
                removed++;
            }
        }

        int added = 0;

        // register loaded chunks around the player (replacing synthetic placeholders)
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                long key = ChunkPos.asLong(cx, cz);
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }

                if (registered.contains(key)) {
                    if (!synthetic.contains(key)) {
                        continue;
                    }

                    try {
                        oceanWorld.removeChunkColumn(cx, cz);
                    } catch (Throwable ignored) {
                    }

                    registered.remove(key);
                    synthetic.remove(key);
                }

                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk != null) {
                    registerOceanChunk(oceanWorld, chunk, cx, cz);
                    registered.add(key);
                    invalidateAround(forced, cx, cz);
                    added++;
                }
            }
        }

        // surround real columns with empty boundary columns where no chunk is loaded
        for (long key : registered.toLongArray()) {
            if (synthetic.contains(key)) {
                continue;
            }

            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }

                    int nx = cx + dx;
                    int nz = cz + dz;
                    long neighbourKey = ChunkPos.asLong(nx, nz);
                    if (!registered.contains(neighbourKey) && !level.hasChunk(nx, nz)) {
                        registerEmptyColumn(oceanWorld, level, nx, nz);
                        registered.add(neighbourKey);
                        synthetic.add(neighbourKey);
                        invalidateAround(forced, nx, nz);
                        added++;
                    }
                }
            }
        }

        return added + removed;
    }

    private static void invalidateAround(LongOpenHashSet forced, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                forced.remove(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
    }

    private static void watchdog(ClientLevel level) {
        try {
            OceanWorld oceanWorld = oceanWorldOf(level);
            if (oceanWorld == null) {
                return;
            }

            LongOpenHashSet registered = registrations.get(oceanWorld);
            if (registered == null || registered.isEmpty()) {
                return;
            }

            if (!oceanWorld.getOceanMeshes().isEmpty()) {
                return;
            }

            LongOpenHashSet forced = forcedFor(oceanWorld);
            if (forced.isEmpty()) {
                return;
            }

            forced.clear();
            LOGGER.debug(
                "[IPPhysicsOcean] watchdog: world=@{} had {} columns but 0 meshes - rebuilding surfaces",
                Integer.toHexString(System.identityHashCode(oceanWorld)), registered.size()
            );
            repairAndSchedule(level);
        } catch (Throwable ignored) {
        }
    }

    private static void registerOceanChunk(OceanWorld oceanWorld, LevelChunk chunk, int cx, int cz) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getLevel().getMinBuildHeight() / 16;
        List<OceanChunkCreator> creators = new ArrayList<>();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int sectionY = i + minSectionY;
            if (!section.hasOnlyAir()) {
                creators.add(new OceanChunkCreator(section.getStates().copy(), cx, sectionY, cz));
            } else {
                creators.add(new OceanChunkCreator((byte) 0, cx, sectionY, cz));
            }
        }

        if (!creators.isEmpty()) {
            oceanWorld.addChunkColumn(creators, cx, cz);
        }
    }

    private static void registerEmptyColumn(OceanWorld oceanWorld, ClientLevel level, int cx, int cz) {
        int minSectionY = level.getMinBuildHeight() / 16;
        int sectionCount = level.getSectionsCount();
        List<OceanChunkCreator> creators = new ArrayList<>(sectionCount);

        for (int i = 0; i < sectionCount; i++) {
            creators.add(new OceanChunkCreator((byte) 0, cx, i + minSectionY, cz));
        }

        if (!creators.isEmpty()) {
            oceanWorld.addChunkColumn(creators, cx, cz);
        }
    }

    private static void repairAndSchedule(ClientLevel level) {
        OceanWorld oceanWorld = oceanWorldOf(level);
        if (oceanWorld == null) {
            return;
        }

        OceanProcessor processor;
        try {
            processor = PhysicsModInternals.oceanProcessorOf(oceanWorld);
        } catch (Throwable e) {
            LOGGER.debug("[IPPhysicsOcean] cannot reach ocean processor: {}", String.valueOf(e));
            return;
        }

        if (processor == null) {
            return;
        }

        LongOpenHashSet forced = forcedFor(oceanWorld);
        processor.queueEvent(() -> {
            try {
                LongSet processColumns = PhysicsModInternals.processChunkColumnsOf(processor);

                for (OceanChunk chunk : new ArrayList<>(processor.loadedChunks.values())) {
                    if (chunk == null) {
                        continue;
                    }

                    int loadedNeighbours = 0;

                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && processor.getChunk(chunk.x + dx, chunk.y, chunk.z + dz) != null) {
                                loadedNeighbours++;
                            }
                        }
                    }

                    if (loadedNeighbours == 8) {
                        if (chunk.getLoadedNeighbourCount() != 8) {
                            chunk.setLoadedNeighbourCount(processor, (byte) 8);
                        }

                        if (processColumns != null) {
                            long key = ChunkPos.asLong(chunk.x, chunk.z);
                            if (!forced.contains(key)) {
                                processColumns.add(Index.oceanLayerChunk(chunk.x, chunk.z));
                                forced.add(key);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.debug("[IPPhysicsOcean] scheduling repair failed: {}", String.valueOf(e));
            }
        });
    }

    /**
     * Drops the cached per-dimension ocean shaders; called by {@code MixinPhysicsModMainRenderer}
     * after a Physics Mod liquid/cloth pass threw (stale Iris shader after a pipeline rebuild).
     */
    public static void forgetCachedOceanShaders() {
        try {
            mainOceanShader.clear();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reflective reads of two private Physics Mod members (Physics Mod is closed-source, so there is
     * no accessor to use): {@code OceanWorld.processor} and {@code OceanProcessor.processChunkColumns}.
     * The {@link Field} handles are resolved once and cached.
     */
    private static final class PhysicsModInternals {
        private static Field oceanWorldProcessorField;
        private static Field processorChunkColumnsField;

        private PhysicsModInternals() {
        }

        /** {@code OceanWorld.processor}: the processor that owns this ocean world's chunks. */
        static OceanProcessor oceanProcessorOf(OceanWorld oceanWorld) throws Exception {
            if (oceanWorldProcessorField == null) {
                Field field = OceanWorld.class.getDeclaredField("processor");
                field.setAccessible(true);
                oceanWorldProcessorField = field;
            }

            return (OceanProcessor) oceanWorldProcessorField.get(oceanWorld);
        }

        /** {@code OceanProcessor.processChunkColumns}: the set of column indices queued for (re)processing. */
        static LongSet processChunkColumnsOf(OceanProcessor processor) throws Exception {
            if (processorChunkColumnsField == null) {
                Field field = OceanProcessor.class.getDeclaredField("processChunkColumns");
                field.setAccessible(true);
                processorChunkColumnsField = field;
            }

            return (LongSet) processorChunkColumnsField.get(processor);
        }
    }
}
