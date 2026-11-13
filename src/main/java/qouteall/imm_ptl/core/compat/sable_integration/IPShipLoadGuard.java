/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPMixinReport;

/**
 * Guards ship moves against other mods that change how Sable loads a plot. {@link IPSableBridge#tryLoad} edits the
 * saved plot for its destination (section keys, plot, block positions) and then lets Sable load it; a mod that edits
 * the plot on load as well can put the blocks somewhere else, and Sable then drops every block entity that finds air.
 * Dimensional Sable 1.1.0 did that to every ship crossing into a level of another height.
 * <ul>
 * <li>{@link #landedWhole} compares every saved block with the loaded ship; a move that lost blocks is undone (the
 * ship goes back to its own level, or stays in the teleport journal), so a change in another mod costs a crossing and
 * never a ship.</li>
 * <li>{@link #audit}, at server start, names every other mod's mixin into that load path: known ones at info, others
 * at warn, so a report's log shows the mod before anything goes wrong.</li>
 * </ul>
 */
public final class IPShipLoadGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** As Sable's {@code ServerLevelPlot.BLOCK_STATE_CODEC}. */
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
        Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState()
    );

    /** Sable methods a moved ship's plot passes through, by class, in a fixed order for the log. */
    private static final List<Map.Entry<Class<?>, Set<String>>> LOAD_PATH = List.of(
        Map.entry(ServerLevelPlot.class, Set.of("load")),
        Map.entry(SubLevelSerializer.class, Set.of("fromData", "fullyLoad"))
    );

    /** Other mods' mixins into the load path that {@link IPSableBridge#tryLoad} accounts for, with the version checked. */
    private static final Map<String, Known> KNOWN = Map.of(
        "dev.egg.dimensionalsable.mixin.ServerLevelPlotMixin",
        new Known("dimensional_sable", "1.1.0", "re-keys sections by min_section_y; set for the destination before loading")
    );

    private record Known(String modId, String checkedVersion, String note) {
    }

    private IPShipLoadGuard() {
    }

    /**
     * Whether every block of the saved plot is in the loaded ship where the plot puts it (its sections are keyed for
     * the destination by then). Logs what was lost otherwise.
     */
    static boolean landedWhole(ServerSubLevel loaded, CompoundTag plotTag) {
        LevelPlot plot = loaded.getPlot();
        CompoundTag chunks = plotTag.getCompound("chunks");
        int saved = 0;
        int missing = 0;
        Set<String> lostSections = new TreeSet<>();
        for (String chunkKey : chunks.getAllKeys()) {
            long chunkPos;
            try {
                chunkPos = Long.parseLong(chunkKey);
            } catch (NumberFormatException e) {
                continue;
            }
            LevelChunk chunk = plot.getChunk(new ChunkPos(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos)));
            CompoundTag sections = chunks.getCompound(chunkKey).getCompound("sections");
            for (String sectionKey : sections.getAllKeys()) {
                int index;
                try {
                    index = Integer.parseInt(sectionKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                Optional<PalettedContainer<BlockState>> states = BLOCK_STATE_CODEC
                    .parse(NbtOps.INSTANCE, sections.getCompound(sectionKey).getCompound("block_states"))
                    .resultOrPartial(error -> {
                    });
                if (states.isEmpty()) {
                    continue;
                }
                LevelChunkSection section = chunk == null || index < 0 || index >= chunk.getSectionsCount() ? null : chunk.getSection(index);
                int lostHere = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!states.get().get(x, y, z).isAir()) {
                                saved++;
                                if (section == null || section.getBlockState(x, y, z).isAir()) {
                                    lostHere++;
                                }
                            }
                        }
                    }
                }
                if (lostHere > 0) {
                    missing += lostHere;
                    lostSections.add(chunkKey + "/" + sectionKey);
                }
            }
        }
        if (missing == 0) {
            return true;
        }
        LOGGER.error(
            "[IPShipPortal] ship {} loaded in {} without {} of its {} blocks (sections {}): another mod changes how Sable "
                + "loads ships (see '[IPShipPortal] ... Sable's ship loading' at server start); the move is undone",
            loaded.getUniqueId(), loaded.getLevel().dimension().location(), missing, saved, lostSections
        );
        return false;
    }

    /**
     * Logs every other mod's mixin into the methods of {@link #LOAD_PATH} ({@link IPMixinReport#otherModsMixins}).
     * Never throws.
     */
    public static void audit() {
        try {
            List<String> checked = new ArrayList<>();
            int hooks = 0;
            for (Map.Entry<Class<?>, Set<String>> target : LOAD_PATH) {
                Map<String, Set<String>> mixins = IPMixinReport.otherModsMixins(target.getKey(), target.getValue());
                for (Map.Entry<String, Set<String>> mixin : mixins.entrySet()) {
                    if (!mixin.getValue().isEmpty()) {
                        hooks++;
                        report(mixin.getKey(), target.getKey().getSimpleName() + "." + String.join("/", mixin.getValue()));
                    }
                }
                checked.add(target.getKey().getSimpleName() + " " + mixins.size());
            }
            LOGGER.info("[IPShipPortal] other mods' mixins checked for Sable's ship loading ({}): {} hook it", String.join(", ", checked), hooks);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not list other mods' hooks into Sable's ship loading", e);
        }
    }

    private static void report(String mixin, String where) {
        Known known = KNOWN.get(mixin);
        if (known == null) {
            LOGGER.warn(
                "[IPShipPortal] {} changes Sable's ship loading at {}, which ship crossings were not checked against: "
                    + "every crossing checks the ship's blocks after loading and is undone if any are missing",
                mixin, where
            );
            return;
        }
        String version = ModList.get().getModContainerById(known.modId())
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("?");
        if (version.equals(known.checkedVersion())) {
            LOGGER.info("[IPShipPortal] {} {} hooks Sable's ship loading at {}: handled ({})", known.modId(), version, where, known.note());
        } else {
            LOGGER.warn(
                "[IPShipPortal] {} {} hooks Sable's ship loading at {}; checked against {} only ({}): crossings check "
                    + "the ship's blocks after loading and are undone if any are missing",
                known.modId(), version, where, known.checkedVersion(), known.note()
            );
        }
    }
}
