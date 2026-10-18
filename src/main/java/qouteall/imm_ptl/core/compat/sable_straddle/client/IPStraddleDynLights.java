/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.IPDynLightsSuppression;
import qouteall.imm_ptl.core.compat.IPSodiumDynamicLightsExt;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lamps on a straddling ship light both halves, with Create Dynamic Lights.
 * <p>
 * Create Dynamic Lights gives every light-emitting block of the player's level's ships a SodiumDynamicLights source
 * at its position in that level. While a ship straddles a portal, the part past the plane is drawn in the destination,
 * so its lamps lit nothing there, and lit the ship's own world behind the portal where that part is hidden. Here, per
 * straddle session:
 * <ul>
 * <li>a lamp past the plane gets a source in the destination, at its position mapped through the portal, and Create
 * Dynamic Lights' source of it is left out of the light ({@link IPDynLightsSuppression});</li>
 * <li>a lamp before the plane keeps Create Dynamic Lights' source when the ship is in the player's level; for a ship
 * of another level (the player is on the destination side, where Create Dynamic Lights does not look) it gets one of
 * ours in the ship's level.</li>
 * </ul>
 * Settings follow Create Dynamic Lights' client config (on/off, luminance multiplier, emission threshold, source cap,
 * scan interval), read by reflection; nothing happens without Create Dynamic Lights. Render thread only.
 */
public final class IPStraddleDynLights {
    private static final Logger LOGGER = LogUtils.getLogger();

    private record LampKey(UUID ship, ResourceKey<Level> shipDim, UUID portal, long plotPos) {
    }

    private record Lamp(BlockPos plotPos, int emission) {
    }

    private static final class ShipLamps {
        long scannedAt;
        List<Lamp> lamps = List.of();
    }

    private record ShipKey(UUID ship, ResourceKey<Level> shipDim) {
    }

    private static final Map<LampKey, StraddleLight> LIGHTS = new HashMap<>();
    private static final Map<ShipKey, ShipLamps> SCANS = new HashMap<>();
    private static boolean loggedFirst;

    private IPStraddleDynLights() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> clear());
    }

    private static void clear() {
        for (StraddleLight light : LIGHTS.values()) {
            light.remove();
        }
        LIGHTS.clear();
        SCANS.clear();
        IPDynLightsSuppression.suppressed = Set.of();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || SodiumDynamicLights.get() == null) {
            return;
        }
        List<IPStraddleClientSessions.Session> sessions = IPStraddleClientSessions.renderSessions();
        CdlConfig config = sessions.isEmpty() ? null : CdlConfig.read();
        if (sessions.isEmpty() || config == null || !config.enabled) {
            if (!LIGHTS.isEmpty() || !IPDynLightsSuppression.suppressed.isEmpty()) {
                clear();
            }
            return;
        }

        Map<ShipKey, List<IPStraddleClientSessions.Session>> byShip = new HashMap<>();
        for (IPStraddleClientSessions.Session session : sessions) {
            byShip.computeIfAbsent(new ShipKey(session.ship(), session.shipDim()), k -> new ArrayList<>()).add(session);
        }

        Set<LampKey> seen = new HashSet<>();
        Map<ClientSubLevel, Set<BlockPos>> farLampsOfPlayerLevelShips = new IdentityHashMap<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<ShipKey, List<IPStraddleClientSessions.Session>> entry : byShip.entrySet()) {
            ShipKey shipKey = entry.getKey();
            ClientLevel shipLevel = ClientWorldLoader.getOptionalWorld(shipKey.shipDim());
            SubLevelContainer container = shipLevel == null ? null : SubLevelContainer.getContainer(shipLevel);
            if (container == null || !(container.getSubLevel(shipKey.ship()) instanceof ClientSubLevel ship) || ship.isRemoved()) {
                continue;
            }
            ShipLamps scan = SCANS.computeIfAbsent(shipKey, k -> new ShipLamps());
            if (now - scan.scannedAt >= config.scanIntervalMs) {
                scan.scannedAt = now;
                scan.lamps = scanLamps(ship, config);
            }
            if (scan.lamps.isEmpty()) {
                continue;
            }
            boolean createDynLightsCoversShip = shipLevel == mc.level;
            Pose3dc pose = ship.logicalPose();
            for (Lamp lamp : scan.lamps) {
                Vec3 pos = pose.transformPosition(Vec3.atCenterOf(lamp.plotPos()));
                int luminance = config.luminance(lamp.emission());
                boolean far = false;
                for (IPStraddleClientSessions.Session session : entry.getValue()) {
                    if (session.normal().dot(pos.subtract(session.origin())) <= 0) {
                        continue;
                    }
                    far = true;
                    ClientLevel dest = ClientWorldLoader.getOptionalWorld(session.destDim());
                    if (dest != null) {
                        LampKey key = new LampKey(shipKey.ship(), shipKey.shipDim(), session.portal(), lamp.plotPos().asLong());
                        seen.add(key);
                        put(key, dest, session.isometry().mapPoint(pos), luminance);
                    }
                }
                if (far && createDynLightsCoversShip) {
                    farLampsOfPlayerLevelShips.computeIfAbsent(ship, s -> new HashSet<>()).add(lamp.plotPos());
                }
                if (!far && !createDynLightsCoversShip) {
                    LampKey key = new LampKey(shipKey.ship(), shipKey.shipDim(), null, lamp.plotPos().asLong());
                    seen.add(key);
                    put(key, shipLevel, pos, luminance);
                }
            }
        }

        LIGHTS.entrySet().removeIf(entry -> {
            if (seen.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
        SCANS.keySet().retainAll(byShip.keySet());
        IPDynLightsSuppression.suppressed = findCreateDynLightsSources(farLampsOfPlayerLevelShips);
    }

    private static void put(LampKey key, ClientLevel level, Vec3 pos, int luminance) {
        StraddleLight light = LIGHTS.get(key);
        if (light == null || light.level != level) {
            if (light != null) {
                light.remove();
            }
            light = new StraddleLight(level);
            LIGHTS.put(key, light);
            if (!loggedFirst) {
                loggedFirst = true;
                LOGGER.info("[IPStraddleDynLights] lighting a straddling ship's lamps on both sides (first: ship {} lamp in {} at {})",
                    key.ship(), level.dimension().location(), pos);
            }
        }
        light.set(pos, luminance);
    }

    private static List<Lamp> scanLamps(ClientSubLevel ship, CdlConfig config) {
        List<Lamp> lamps = new ArrayList<>();
        for (PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            ChunkPos chunkPos = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                int minY = chunk.getSectionYFromSectionIndex(index) << 4;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int emission = section.getBlockState(x, y, z).getLightEmission();
                            if (emission >= config.emissionLowerLimit) {
                                lamps.add(new Lamp(new BlockPos(chunkPos.getMinBlockX() + x, minY + y, chunkPos.getMinBlockZ() + z), emission));
                                if (lamps.size() >= config.maxSources) {
                                    return lamps;
                                }
                            }
                        }
                    }
                }
            }
        }
        return lamps;
    }

    /** Create Dynamic Lights' sources of the given lamps, found by their ship and plot position. */
    private static Set<Object> findCreateDynLightsSources(Map<ClientSubLevel, Set<BlockPos>> lamps) {
        if (lamps.isEmpty() || !CdlSourceFields.available()) {
            return Set.of();
        }
        Set<Object> found = Collections.newSetFromMap(new IdentityHashMap<>());
        ((IPSodiumDynamicLightsExt) SodiumDynamicLights.get()).ip_forEachSource(source -> {
            if (source.getClass() != CdlSourceFields.sourceClass) {
                return;
            }
            Object ship = CdlSourceFields.ship(source);
            Set<BlockPos> farLamps = ship == null ? null : lamps.get(ship);
            if (farLamps != null && farLamps.contains(CdlSourceFields.plotPos(source))) {
                found.add(source);
            }
        });
        return found.isEmpty() ? Set.of() : Collections.unmodifiableSet(found);
    }

    /** A lamp of a straddling ship, lit in the level it is drawn in. */
    private static final class StraddleLight implements DynamicLightSource {
        final ClientLevel level;
        private volatile double x;
        private volatile double y;
        private volatile double z;
        private volatile int luminance;
        private boolean removed;
        private double previousX = Double.NaN;
        private double previousY;
        private double previousZ;
        private int previousLuminance;
        private LongOpenHashSet trackedSections = new LongOpenHashSet();

        StraddleLight(ClientLevel level) {
            this.level = level;
        }

        void set(Vec3 pos, int luminance) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.luminance = luminance;
            if (!removed && !sdl$isDynamicLightEnabled()) {
                sdl$setDynamicLightEnabled(true);
            }
        }

        void remove() {
            removed = true;
            luminance = 0;
            sdl$setDynamicLightEnabled(false);
        }

        @Override
        public double sdl$getDynamicLightX() {
            return x;
        }

        @Override
        public double sdl$getDynamicLightY() {
            return y;
        }

        @Override
        public double sdl$getDynamicLightZ() {
            return z;
        }

        @Override
        public Level sdl$getDynamicLightLevel() {
            return level;
        }

        @Override
        public void sdl$resetDynamicLight() {
            previousLuminance = 0;
        }

        @Override
        public int sdl$getLuminance() {
            return removed ? 0 : luminance;
        }

        @Override
        public void sdl$dynamicLightTick() {
        }

        @Override
        public boolean sdl$shouldUpdateDynamicLight() {
            return true;
        }

        @Override
        public boolean sodiumdynamiclights$updateDynamicLight(@NotNull LevelRenderer renderer) {
            int currentLuminance = sdl$getLuminance();
            double cx = x;
            double cy = y;
            double cz = z;
            boolean changed = Double.isNaN(previousX)
                || Math.abs(cx - previousX) > 0.1 || Math.abs(cy - previousY) > 0.1 || Math.abs(cz - previousZ) > 0.1
                || currentLuminance != previousLuminance;
            if (!changed) {
                return false;
            }
            previousX = cx;
            previousY = cy;
            previousZ = cz;
            previousLuminance = currentLuminance;
            LongOpenHashSet sections = new LongOpenHashSet();
            if (currentLuminance > 0) {
                // the light reaches 7.75 blocks: its section and the neighbours on the sides it is nearer to
                int bx = Mth.floor(cx);
                int by = Mth.floor(cy);
                int bz = Mth.floor(cz);
                int sx = SectionPos.blockToSectionCoord(bx);
                int sy = SectionPos.blockToSectionCoord(by);
                int sz = SectionPos.blockToSectionCoord(bz);
                int dx = (bx & 15) >= 8 ? 1 : -1;
                int dy = (by & 15) >= 8 ? 1 : -1;
                int dz = (bz & 15) >= 8 ? 1 : -1;
                for (int i = 0; i < 8; i++) {
                    sections.add(SectionPos.asLong(sx + ((i & 1) != 0 ? dx : 0), sy + ((i & 2) != 0 ? dy : 0), sz + ((i & 4) != 0 ? dz : 0)));
                }
            }
            LevelRenderer own = ownRenderer();
            if (own != null) {
                for (long section : sections) {
                    rebuild(own, section);
                }
                for (long section : trackedSections) {
                    if (!sections.contains(section)) {
                        rebuild(own, section);
                    }
                }
            }
            trackedSections = sections;
            return true;
        }

        @Override
        public void sodiumdynamiclights$scheduleTrackedChunksRebuild(@NotNull LevelRenderer renderer) {
            LevelRenderer own = ownRenderer();
            if (own != null) {
                for (long section : trackedSections) {
                    rebuild(own, section);
                }
            }
        }

        /**
         * The renderer of this light's level (SodiumDynamicLights hands removals the player's); null once the level is
         * gone. Never creates a level or renderer: removals also run during IP's cleanup on leaving a world.
         */
        private LevelRenderer ownRenderer() {
            Minecraft mc = Minecraft.getInstance();
            if (level == mc.level) {
                return mc.levelRenderer;
            }
            return ClientWorldLoader.getIsInitialized() && ClientWorldLoader.getClientWorlds().contains(level)
                ? ClientWorldLoader.WORLD_RENDERER_MAP.get(level.dimension()) : null;
        }

        private static void rebuild(LevelRenderer renderer, long section) {
            SodiumDynamicLights.scheduleChunkRebuild(renderer,
                SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
    }

    /** Create Dynamic Lights' client config, read by reflection (its port has no API). */
    private static final class CdlConfig {
        private static boolean failed;
        private static Object client;
        private static final Map<String, Field> FIELDS = new HashMap<>();

        boolean enabled;
        double luminanceMultiplier;
        int emissionLowerLimit;
        int maxSources;
        int scanIntervalMs;

        int luminance(int emission) {
            return Mth.clamp((int) (emission * luminanceMultiplier), 0, 15);
        }

        static CdlConfig read() {
            if (failed) {
                return null;
            }
            try {
                if (client == null) {
                    client = Class.forName("top.leonx.dynlight.config.CreateDynLightAllConfigs").getMethod("client").invoke(null);
                    if (client == null) {
                        return null;
                    }
                }
                CdlConfig config = new CdlConfig();
                config.enabled = (Boolean) value("enableSodiumDynamicLight") && (Boolean) value("enableSableDynamicLight");
                config.luminanceMultiplier = ((Number) value("luminanceMultiplier")).doubleValue();
                config.emissionLowerLimit = ((Number) value("sableLightEmissionLowerLimit")).intValue();
                config.maxSources = ((Number) value("maxSableLightSources")).intValue();
                config.scanIntervalMs = ((Number) value("sableScanInterval")).intValue();
                return config;
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed = true;
                LOGGER.warn("[IPStraddleDynLights] cannot read Create Dynamic Lights' config; straddling ships' lamps light only their own side", e);
                return null;
            }
        }

        private static Object value(String name) throws ReflectiveOperationException {
            Field field = FIELDS.get(name);
            if (field == null) {
                field = client.getClass().getField(name);
                FIELDS.put(name, field);
            }
            Object configValue = field.get(client);
            Method get = configValue.getClass().getMethod("get");
            return get.invoke(configValue);
        }
    }

    /** The ship and plot position of Create Dynamic Lights' Sable light sources, read by reflection. */
    private static final class CdlSourceFields {
        static Class<?> sourceClass;
        private static Field subLevel;
        private static Field plotPos;
        private static boolean tried;

        static boolean available() {
            if (!tried) {
                tried = true;
                try {
                    sourceClass = Class.forName("top.leonx.dynlight.sodium.sable.SableDynLightSource");
                    subLevel = sourceClass.getDeclaredField("subLevel");
                    subLevel.setAccessible(true);
                    plotPos = sourceClass.getDeclaredField("plotPos");
                    plotPos.setAccessible(true);
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    sourceClass = null;
                    LOGGER.warn("[IPStraddleDynLights] cannot read Create Dynamic Lights' ship lights; far-half lamps also light behind the portal", e);
                }
            }
            return sourceClass != null;
        }

        static Object ship(Object source) {
            try {
                return subLevel.get(source);
            }
            catch (IllegalAccessException e) {
                return null;
            }
        }

        static BlockPos plotPos(Object source) {
            try {
                return (BlockPos) plotPos.get(source);
            }
            catch (IllegalAccessException e) {
                return null;
            }
        }
    }
}
