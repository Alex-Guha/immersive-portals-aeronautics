/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portals of shattered clusters that could not break with the rest: not loaded, or on a ship that is not. They finish
 * when they can ({@link IPShipPortalAnchors}, end of the server tick). Saved, so a restart in between loses nothing.
 */
final class IPShipPortalBrokenLinks extends SavedData {
    private static final String FILE_ID = "ip_aeronautics_broken_links";

    private final Set<UUID> portals = new HashSet<>();

    static IPShipPortalBrokenLinks of(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(IPShipPortalBrokenLinks::new, IPShipPortalBrokenLinks::load, null), FILE_ID);
    }

    private static IPShipPortalBrokenLinks load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final IPShipPortalBrokenLinks links = new IPShipPortalBrokenLinks();
        for (final Tag portal : tag.getList("portals", Tag.TAG_INT_ARRAY)) {
            links.portals.add(NbtUtils.loadUUID(portal));
        }
        return links;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final UUID portal : this.portals) {
            list.add(NbtUtils.createUUID(portal));
        }
        tag.put("portals", list);
        return tag;
    }

    boolean isEmpty() {
        return this.portals.isEmpty();
    }

    boolean contains(final UUID portal) {
        return this.portals.contains(portal);
    }

    List<UUID> portals() {
        return List.copyOf(this.portals);
    }

    void add(final UUID portal) {
        if (this.portals.add(portal)) {
            this.setDirty();
        }
    }

    void remove(final UUID portal) {
        if (this.portals.remove(portal)) {
            this.setDirty();
        }
    }
}
