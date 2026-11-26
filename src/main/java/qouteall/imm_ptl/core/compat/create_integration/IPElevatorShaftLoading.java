/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Keeps an elevator's whole shaft through portals loaded for the players near any part of it. IP loads what a player
 * sees through one portal, and through a second only near where they look and only while the server keeps up
 * ({@code ChunkVisibility}): two portals away from the pulley, its ship unloaded with the car, and a portal two away
 * from the player was not found, so the shaft ended there and the floors past it were missing from the controls.
 * <p>
 * The places of a shaft are the pulley's and the far end of each of its portals, where the shaft goes on (and the next
 * portal is), each with the chunks under the ships there. Each player in a place's level whose view reaches a place
 * gets an IP chunk loader for every place of that shaft: loaded, ticking and sent to them, so their client also gets
 * the pulley's updates. The places stay known while the pulley is unloaded, so a player near the far end loads it again.
 */
public final class IPElevatorShaftLoading {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Chunks around a place at least: a ship there not loaded yet, a car's width, a column off the chunk's middle. */
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 8;
    private static final int PLAYER_TICKS = 20;
    /** Held this long without its pulley reporting, a shaft is dropped: the pulley is gone (broken, its ship taken apart). */
    private static final int ORPHAN_TICKS = 600;

    private record Key(ResourceKey<Level> dim, BlockPos pulley) {
        @Override
        public String toString() {
            return pulley.toShortString() + " in " + dim.location();
        }
    }

    private static final class Shaft {
        private List<ChunkLoader> places = List.of();
        private long lastUpdate;
        private long heldSince = -1;
    }

    /** The loaders a player was given (IP removes them by reference). */
    private record Held(ServerPlayer player, List<ChunkLoader> loaders) {
    }

    private static final Map<Key, Shaft> SHAFTS = new HashMap<>();
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private IPElevatorShaftLoading() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            SHAFTS.clear();
            HELD.clear();
        });
    }

    /** A pulley with a shaft through portals, at each scan: its places now. */
    static void update(final ServerLevel level, final BlockPos pulley, final List<IPElevatorShaft> chain) {
        final Key key = new Key(level.dimension(), pulley.immutable());
        final Shaft shaft = SHAFTS.computeIfAbsent(key, k -> new Shaft());
        final List<ChunkLoader> places = new ArrayList<>();
        final ChunkLoader home = place(level, IPElevatorFrames.toWorld(level, Vec3.atCenterOf(pulley)));
        if (home != null) {
            places.add(home);
        }
        ServerLevel at = level;
        for (int k = 0; k < chain.size(); k++) {
            final IPElevatorShaft step = chain.get(k);
            ChunkLoader far = null;
            // the portal is in the level above it; its far end, in world coordinates, is known while its ship is unloaded
            if (at != null && at.getEntity(step.portalId()) instanceof Portal portal) {
                final ServerLevel dest = level.getServer().getLevel(portal.getDestDim());
                far = dest == null ? null : place(dest, portal.getDestPos());
            }
            if (far == null && k + 1 < shaft.places.size() && shaft.places.get(k + 1).dimension() == step.farDim()) {
                far = shaft.places.get(k + 1); // its portal not loaded now: where it was
            }
            if (far != null) {
                places.add(far);
            }
            at = level.getServer().getLevel(step.farDim());
        }
        shaft.lastUpdate = level.getServer().getTickCount();
        if (!places.equals(shaft.places)) {
            LOGGER.info("[IPElevator] pulley {}: its shaft is held for the players near it at {}", key, describe(places));
            shaft.places = List.copyOf(places);
        }
    }

    /** The pulley stopped, or its shaft goes through no portal any more. */
    static void forget(final ServerLevel level, final BlockPos pulley) {
        if (SHAFTS.remove(new Key(level.dimension(), pulley)) != null) {
            LOGGER.info("[IPElevator] pulley {} in {}: its shaft is no longer held", pulley.toShortString(), level.dimension().location());
        }
    }

    private static @Nullable ChunkLoader place(final ServerLevel level, final Vec3 world) {
        if (IPSableCompatHelper.isPlotSpacePosition(world.x, world.z)) {
            return null; // on a ship not loaded: no place in the world
        }
        final ChunkPos chunk = new ChunkPos(BlockPos.containing(world));
        final int radius = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, IPElevatorFrames.shipChunkRadius(level, chunk.x, chunk.z) + 1));
        return new ChunkLoader(level.dimension(), chunk.x, chunk.z, radius);
    }

    private static boolean near(final ServerPlayer player, final List<ChunkLoader> places) {
        final ResourceKey<Level> dim = player.level().dimension();
        final ChunkPos at = player.chunkPosition();
        final int view = McHelper.getPlayerLoadDistance(player);
        for (final ChunkLoader place : places) {
            if (place.dimension() == dim && Math.max(Math.abs(place.x() - at.x), Math.abs(place.z() - at.z)) <= view + place.radius()) {
                return true;
            }
        }
        return false;
    }

    private static void onServerTick(final MinecraftServer server) {
        final long now = server.getTickCount();
        if (now % PLAYER_TICKS != 0 || (SHAFTS.isEmpty() && HELD.isEmpty())) {
            return;
        }
        final List<ServerPlayer> players = server.getPlayerList().getPlayers();
        final Map<ServerPlayer, Set<ChunkLoader>> wanted = new HashMap<>();
        for (final Iterator<Map.Entry<Key, Shaft>> it = SHAFTS.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Key, Shaft> entry = it.next();
            final Shaft shaft = entry.getValue();
            boolean held = false;
            for (final ServerPlayer player : players) {
                if (near(player, shaft.places)) {
                    held = true;
                    wanted.computeIfAbsent(player, p -> new LinkedHashSet<>()).addAll(shaft.places);
                }
            }
            if (!held) {
                shaft.heldSince = -1;
            } else if (shaft.heldSince < 0) {
                shaft.heldSince = now;
            } else if (now - Math.max(shaft.heldSince, shaft.lastUpdate) > ORPHAN_TICKS) {
                LOGGER.info("[IPElevator] pulley {}: no longer there while its shaft was held, the shaft is dropped", entry.getKey());
                it.remove();
            }
        }
        for (final Iterator<Map.Entry<UUID, Held>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            final Held held = it.next().getValue();
            if (held.player().isRemoved() || !players.contains(held.player())) {
                it.remove(); // gone, or a new player object (IP dropped the old one's loaders with it)
            }
        }
        final Set<ServerPlayer> touched = new java.util.HashSet<>(wanted.keySet());
        for (final Held held : HELD.values()) {
            touched.add(held.player());
        }
        for (final ServerPlayer player : touched) {
            final Set<ChunkLoader> want = wanted.getOrDefault(player, Set.of());
            final Held before = HELD.get(player.getUUID());
            final List<ChunkLoader> had = before == null ? List.of() : before.loaders();
            if (new LinkedHashSet<>(had).equals(want)) {
                continue;
            }
            final List<ChunkLoader> kept = new ArrayList<>();
            for (final ChunkLoader loader : had) {
                if (want.contains(loader)) {
                    kept.add(loader);
                } else {
                    ImmPtlChunkTracking.removePerPlayerAdditionalChunkLoader(player, loader);
                }
            }
            for (final ChunkLoader loader : want) {
                if (!kept.contains(loader)) {
                    ImmPtlChunkTracking.addPerPlayerAdditionalChunkLoader(player, loader);
                    kept.add(loader);
                }
            }
            if (kept.isEmpty()) {
                HELD.remove(player.getUUID());
            } else {
                HELD.put(player.getUUID(), new Held(player, kept));
            }
            LOGGER.info("[IPElevator] elevator shafts held for {}: {}", player.getGameProfile().getName(),
                kept.isEmpty() ? "none" : describe(kept));
        }
    }

    private static String describe(final List<ChunkLoader> places) {
        final StringBuilder text = new StringBuilder();
        for (final ChunkLoader place : places) {
            text.append(text.isEmpty() ? "" : ", ").append(place.dimension().location().getPath()).append(" chunk ")
                .append(place.x()).append(' ').append(place.z()).append(" r").append(place.radius());
        }
        return text.toString();
    }
}
