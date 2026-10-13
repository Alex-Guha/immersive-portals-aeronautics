/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * Crash-safety journal for ship moves: before a ship is removed from its source dimension an entry
 * ({@code <world>/data/immersive_portals/pending_ship_moves/<uuid>.nbt} with src/dst dim, source min build
 * height and the full {@link SubLevelData} tag) is written; {@link #replayPending} (run on the first
 * traversal tick) reloads entries whose ship exists in neither dimension into the destination.
 */
public final class IPShipTeleportJournal {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_SUFFIX = ".nbt";

    private IPShipTeleportJournal() {
    }

    public static Path pendingDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("immersive_portals").resolve("pending_ship_moves");
    }

    public static void write(MinecraftServer server, UUID subUuid, ResourceLocation srcDim, ResourceLocation dstDim, int srcMinY, SubLevelData data) {
        try {
            Path dir = pendingDir(server);
            Files.createDirectories(dir);
            CompoundTag tag = new CompoundTag();
            tag.putUUID("sub_uuid", subUuid);
            tag.putString("src_dim", srcDim.toString());
            tag.putString("dst_dim", dstDim.toString());
            tag.putInt("src_min_y", srcMinY);
            tag.putLong("created_at", System.currentTimeMillis());
            tag.put("sub_level_data", data.fullTag().copy());
            NbtIo.writeCompressed(tag, dir.resolve(subUuid + FILE_SUFFIX));
        } catch (IOException e) {
            LOGGER.warn("[IPShipPortal] journal: failed to write entry for {}: {}", subUuid, e.toString());
        }
    }

    public static void delete(MinecraftServer server, UUID subUuid) {
        try {
            Files.deleteIfExists(pendingDir(server).resolve(subUuid + FILE_SUFFIX));
        } catch (IOException e) {
            LOGGER.warn("[IPShipPortal] journal: failed to delete entry for {}: {}", subUuid, e.toString());
        }
    }

    public static void replayPending(MinecraftServer server) {
        Path dir = pendingDir(server);
        if (Files.isDirectory(dir)) {
            ArrayList<Path> entries = new ArrayList<>();

            try (Stream<Path> files = Files.list(dir)) {
                files.filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX)).forEach(entries::add);
            } catch (IOException e) {
                LOGGER.warn("[IPShipPortal] journal: failed to list {}: {}", dir, e.toString());
                return;
            }

            if (!entries.isEmpty()) {
                LOGGER.debug("[IPShipPortal] journal: scanning {} pending ship move(s)", entries.size());

                for (Path entry : entries) {
                    try {
                        replayOne(server, entry);
                    } catch (Throwable e) {
                        LOGGER.warn("[IPShipPortal] journal: replay failed for {}: {}", entry, e.toString());
                    }
                }
            }
        }
    }

    private static void replayOne(MinecraftServer server, Path file) {
        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LOGGER.warn("[IPShipPortal] journal: unreadable {}; removing", file);
            tryDelete(file);
            return;
        }

        UUID subUuid;
        ResourceLocation srcDim;
        ResourceLocation dstDim;
        int srcMinY;
        CompoundTag subLevelTag;
        try {
            subUuid = tag.getUUID("sub_uuid");
            srcDim = ResourceLocation.parse(tag.getString("src_dim"));
            dstDim = ResourceLocation.parse(tag.getString("dst_dim"));
            srcMinY = tag.getInt("src_min_y");
            subLevelTag = tag.getCompound("sub_level_data");
        } catch (RuntimeException e) {
            LOGGER.warn("[IPShipPortal] journal: malformed entry {}; removing", file);
            tryDelete(file);
            return;
        }

        ServerLevel dstLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dstDim));
        if (dstLevel != null) {
            ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
            if (dstContainer != null) {
                if (dstContainer.getSubLevel(subUuid) == null && dstContainer.getHoldingChunkMap().getHoldingSubLevel(subUuid) == null) {
                    ServerLevel srcLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, srcDim));
                    if (srcLevel != null) {
                        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
                        if (srcContainer != null && (srcContainer.getSubLevel(subUuid) != null || srcContainer.getHoldingChunkMap().getHoldingSubLevel(subUuid) != null)) {
                            tryDelete(file);
                            return;
                        }
                    }

                    SubLevelData data = SubLevelSerializer.fromData(subLevelTag);
                    if (data == null) {
                        LOGGER.warn("[IPShipPortal] journal: fromData null for {}; removing", subUuid);
                        tryDelete(file);
                    } else {
                        LOGGER.info("[IPShipPortal] journal: recovering lost ship {} into {}", subUuid, dstDim);
                        IPSableBridge.Loaded loaded = IPSableBridge.reloadInDestination(srcMinY, dstLevel, dstContainer, data);
                        if (loaded == null) {
                            LOGGER.warn("[IPShipPortal] journal: recovery load failed for {}; leaving entry for next start", subUuid);
                        } else {
                            LOGGER.info("[IPShipPortal] journal: recovered ship {}", subUuid);
                            tryDelete(file);
                        }
                    }
                } else {
                    tryDelete(file);
                }
            }
        }
    }

    private static void tryDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
