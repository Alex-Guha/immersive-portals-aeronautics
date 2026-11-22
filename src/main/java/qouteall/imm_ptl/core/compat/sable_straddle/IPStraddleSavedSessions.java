/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which portals each ship straddles, kept across a restart and across the ship unloading, so a ship that loads still
 * through a portal gets its session back on the same face ({@link IPStraddleController#considerShip}). Sessions live
 * only in memory, and a new one opens on the face the ship moves into: a ship at rest after loading opened none (both
 * faces of a nether portal waited for a direction), and the first push opened whichever face it went into, the back
 * face of one of two portals facing each other included, whose far half was the ship's whole middle; cut out of its
 * own world there, the ship fell through the ground.
 */
final class IPStraddleSavedSessions extends SavedData {
    private static final String FILE_ID = "ip_aeronautics_straddle_sessions";
    /** Ships kept at most; the oldest go first (a ship removed while unloaded never comes back to clear its entry). */
    private static final int MAX_SHIPS = 512;

    private final Map<UUID, List<UUID>> portalsByShip = new LinkedHashMap<>();

    static IPStraddleSavedSessions of(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(IPStraddleSavedSessions::new, IPStraddleSavedSessions::load, null), FILE_ID);
    }

    private static IPStraddleSavedSessions load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final IPStraddleSavedSessions saved = new IPStraddleSavedSessions();
        for (final Tag entry : tag.getList("ships", Tag.TAG_COMPOUND)) {
            final CompoundTag ship = (CompoundTag) entry;
            final List<UUID> portals = new ArrayList<>();
            for (final Tag portal : ship.getList("portals", Tag.TAG_INT_ARRAY)) {
                portals.add(NbtUtils.loadUUID(portal));
            }
            if (ship.hasUUID("ship") && !portals.isEmpty()) {
                saved.portalsByShip.put(ship.getUUID("ship"), portals);
            }
        }
        return saved;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag ships = new ListTag();
        for (final Map.Entry<UUID, List<UUID>> entry : this.portalsByShip.entrySet()) {
            final CompoundTag ship = new CompoundTag();
            ship.putUUID("ship", entry.getKey());
            final ListTag portals = new ListTag();
            for (final UUID portal : entry.getValue()) {
                portals.add(NbtUtils.createUUID(portal));
            }
            ship.put("portals", portals);
            ships.add(ship);
        }
        tag.put("ships", ships);
        return tag;
    }

    boolean has(final UUID ship, final UUID portal) {
        final List<UUID> portals = this.portalsByShip.get(ship);
        return portals != null && portals.contains(portal);
    }

    List<UUID> portalsOf(final UUID ship) {
        final List<UUID> portals = this.portalsByShip.get(ship);
        return portals == null ? List.of() : List.copyOf(portals);
    }

    void add(final UUID ship, final UUID portal) {
        final List<UUID> portals = this.portalsByShip.computeIfAbsent(ship, s -> new ArrayList<>(2));
        if (!portals.contains(portal)) {
            portals.add(portal);
            this.setDirty();
        }
        if (this.portalsByShip.size() > MAX_SHIPS) {
            final Iterator<UUID> oldest = this.portalsByShip.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    void remove(final UUID ship, final UUID portal) {
        final List<UUID> portals = this.portalsByShip.get(ship);
        if (portals != null && portals.remove(portal)) {
            if (portals.isEmpty()) {
                this.portalsByShip.remove(ship);
            }
            this.setDirty();
        }
    }

    void clear() {
        if (!this.portalsByShip.isEmpty()) {
            this.portalsByShip.clear();
            this.setDirty();
        }
    }
}
