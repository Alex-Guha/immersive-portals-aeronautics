/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.peripheral.alternate_dimension;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The alternate dimensions this world has, kept with the overworld's data (data/immersive_portals_alternate_dimensions.dat).
 * {@link AlternateDimensions} adds them back on every start, as they are not in level.dat.
 */
public class AlternateDimensionStorage extends SavedData {
    public final List<String> dimensionIds = new ArrayList<>();

    public static AlternateDimensionStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                AlternateDimensionStorage::new,
                (tag, registries) -> {
                    AlternateDimensionStorage storage = new AlternateDimensionStorage();
                    ListTag list = tag.getList("dimensions", Tag.TAG_STRING);
                    for (int i = 0; i < list.size(); i++) {
                        storage.dimensionIds.add(list.getString(i));
                    }
                    return storage;
                },
                null
            ),
            "immersive_portals_alternate_dimensions"
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (String id : dimensionIds) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("dimensions", list);
        return tag;
    }
}
