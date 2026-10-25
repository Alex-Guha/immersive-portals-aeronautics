/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

/**
 * Create's portal edges within one dimension. Create joins the two portal-face nodes of a linked pair of portal tracks
 * with a portal edge (zero length, no dimension of its own) only when the two ends are in different dimensions
 * ({@code TrackEdge.interDimensional}, recomputed from the nodes whenever an edge is built); a pair within one dimension
 * would become an ordinary straight edge through the world. The pairs {@link IPCreatePortalTracks} links within one
 * dimension are recorded here as they enter Create's graph ({@code MixinTrackBlock_PortalTracks}: both faces forced to
 * be nodes) and their edges count as portal edges ({@code MixinTrackEdge_SameDimensionPortals}).
 * <p>
 * The server's set is the truth: an edge's flag is saved with Create's graph ({@code IPPortalLink} in the edge's tag) and
 * read back with it. Create's graph sync carries no such flag, so remote clients get the whole set from us, on joining
 * and whenever it changes; a client of an integrated server reads the server's set directly (one JVM).
 */
public final class IPCreatePortalEdges {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPCreatePortalEdges] ";
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEdges.RemoteCallables.";
    /** The flag Create's graph save carries on a same-dimension portal edge. */
    public static final String SAVE_KEY = "IPPortalLink";

    /** An unordered pair of portal-face nodes in one dimension. */
    public record Link(TrackNodeLocation a, TrackNodeLocation b) {
        public static Link of(final TrackNodeLocation first, final TrackNodeLocation second) {
            return order(first, second) <= 0 ? new Link(first, second) : new Link(second, first);
        }

        private static int order(final TrackNodeLocation first, final TrackNodeLocation second) {
            final int byPos = first.compareTo(second);
            return byPos != 0 ? byPos : Integer.compare(first.yOffsetPixels, second.yOffsetPixels);
        }
    }

    /** Immutable snapshots, swapped whole: Create's server and client threads read them. */
    private static volatile Set<Link> links = Set.of();
    private static volatile Set<TrackNodeLocation> faces = Set.of();
    /** Bumped on every change; edges cache their answer against it. */
    private static volatile int version;
    private static boolean dirty;
    private static boolean registered;

    private IPCreatePortalEdges() {
    }

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, event -> replace(Set.of()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> replace(Set.of()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof final ServerPlayer player) {
                send(player);
            }
        });
    }

    public static int version() {
        return version;
    }

    /** Whether any same-dimension portal edge exists (cheap: most worlds have none). */
    public static boolean any() {
        return !links.isEmpty();
    }

    public static boolean isLink(@Nullable final TrackNodeLocation first, @Nullable final TrackNodeLocation second) {
        if (first == null || second == null || links.isEmpty() || first.dimension == null
            || !first.dimension.equals(second.dimension)) {
            return false;
        }
        return links.contains(Link.of(first, second));
    }

    /** All faces, for distance checks (few). */
    public static Set<TrackNodeLocation> faces() {
        return faces;
    }

    /** Server: the pair at these two portal faces is linked (also when read back from Create's saved graph). */
    public static void add(final TrackNodeLocation first, final TrackNodeLocation second) {
        final Link link = Link.of(copy(first), copy(second));
        if (links.contains(link)) {
            return;
        }
        final Set<Link> next = new HashSet<>(links);
        next.add(link);
        replace(next);
        dirty = true;
        LOGGER.info(TAG + "portal edge within {} between the nodes at {} and {}", first.dimension.location(),
            first.getLocation(), second.getLocation());
    }

    private static TrackNodeLocation copy(final TrackNodeLocation location) {
        final TrackNodeLocation copy = TrackNodeLocation.read(location.write(null), null);
        copy.dimension = location.dimension;
        return copy;
    }

    private static synchronized void replace(final Set<Link> next) {
        final Set<TrackNodeLocation> nextFaces = new HashSet<>();
        for (final Link link : next) {
            nextFaces.add(link.a());
            nextFaces.add(link.b());
        }
        links = Set.copyOf(next);
        faces = Set.copyOf(nextFaces);
        version++;
    }

    private static void onServerTick(final MinecraftServer server) {
        if (!dirty) {
            return;
        }
        dirty = false;
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    private static void send(final ServerPlayer player) {
        if (player.server.isSingleplayerOwner(player.getGameProfile())) {
            // the integrated server's own client reads this JVM's set
            return;
        }
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "setLinks", write(links));
    }

    private static CompoundTag write(final Set<Link> set) {
        final ListTag list = new ListTag();
        for (final Link link : set) {
            final CompoundTag entry = new CompoundTag();
            entry.putString("D", link.a().dimension.location().toString());
            entry.put("A", link.a().write(null));
            entry.put("B", link.b().write(null));
            list.add(entry);
        }
        final CompoundTag tag = new CompoundTag();
        tag.put("Links", list);
        return tag;
    }

    private static Set<Link> read(final CompoundTag tag) {
        final Set<Link> set = new HashSet<>();
        for (final Tag element : tag.getList("Links", Tag.TAG_COMPOUND)) {
            final CompoundTag entry = (CompoundTag) element;
            final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.getString("D")));
            final TrackNodeLocation a = TrackNodeLocation.read(entry.getCompound("A"), null);
            final TrackNodeLocation b = TrackNodeLocation.read(entry.getCompound("B"), null);
            a.dimension = dimension;
            b.dimension = dimension;
            set.add(Link.of(a, b));
        }
        return set;
    }

    /** For the log: the links, one per line. */
    public static List<String> describe() {
        final List<String> lines = new ArrayList<>();
        for (final Link link : links) {
            lines.add(link.a().dimension.location() + " " + link.a().getLocation() + " <-> " + link.b().getLocation());
        }
        return lines;
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        public static void setLinks(final CompoundTag tag) {
            if (Minecraft.getInstance().hasSingleplayerServer()) {
                return;
            }
            replace(read(tag));
        }
    }
}
