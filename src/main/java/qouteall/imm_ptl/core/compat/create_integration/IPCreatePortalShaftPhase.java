/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.compat.mixin_create.IPRotationPropagatorInvoker;

/**
 * The angle Create draws a network driven through a portal with. Create draws every turning part at
 * {@code offset + time * speed}, the offset depending on the part's position (every other shaft is turned 22.5
 * degrees, for cogs to mesh), so the two sides of a portal meet at the plane at some fixed angle to each other. The
 * driven side (its end's Create source is the portal block) is shifted to line up with the driving side: by a shift in
 * time, so each of its parts gets {@code C * speed / speed of the driven end} and its cogs still mesh
 * ({@code MixinKineticBlockEntity_PortalShaftPhase}). {@code C} is worked out where the half shafts are
 * ({@link IPCreatePortalShaftHalves}), from the two ends' own offsets and how the portal turns one side onto the other.
 * Client only; Create's offsets are whole degrees.
 */
public final class IPCreatePortalShaftPhase {
    /** At most this many parts of a network are told to redraw when its shift changes. */
    private static final int MAX_REDRAWN = 4096;

    /** A network, by its dimension and Create network id. */
    record Key(ResourceKey<Level> dimension, long network) {
    }

    /** The shift of a driven network: {@code degrees} at its driven end, scaled by speed elsewhere. */
    record Shift(int degrees, WeakReference<KineticBlockEntity> root) {
        boolean sameAs(final Shift other) {
            return other != null && degrees == other.degrees && root.get() == other.root.get();
        }
    }

    /** Replaced whole (read from Flywheel's worker threads while visuals are made). */
    private static volatile Map<Key, Shift> shifts = Map.of();
    /** Our own offset lookups, without the shift. */
    private static final ThreadLocal<boolean[]> RAW = ThreadLocal.withInitial(() -> new boolean[1]);

    private IPCreatePortalShaftPhase() {
    }

    /** The extra angle offset of {@code be}, 0 unless its network is shifted. */
    public static int extraOffset(final KineticBlockEntity be) {
        final Long network = be.network;
        final Map<Key, Shift> current = shifts;
        if (network == null || current.isEmpty() || RAW.get()[0] || be.getLevel() == null || !be.getLevel().isClientSide()) {
            return 0;
        }
        final Shift shift = current.get(new Key(be.getLevel().dimension(), network));
        final KineticBlockEntity root = shift == null ? null : shift.root().get();
        if (root == null || root.isRemoved()) {
            return 0;
        }
        final float rootSpeed = root.getTheoreticalSpeed();
        return rootSpeed == 0 ? 0 : Math.round(shift.degrees() * be.getTheoreticalSpeed() / rootSpeed);
    }

    /** Create's own angle offset of {@code be} about {@code axis}, without our shift. */
    static int rawAngleOffset(final KineticBlockEntity be, final net.minecraft.core.Direction.Axis axis) {
        final boolean[] raw = RAW.get();
        raw[0] = true;
        try {
            return be.getRotationAngleOffset(axis);
        } finally {
            raw[0] = false;
        }
    }

    /** The network {@code be} roots, keyed for {@link #update}. */
    static Key keyOf(final KineticBlockEntity be) {
        return be.network == null || be.getLevel() == null ? null : new Key(be.getLevel().dimension(), be.network);
    }

    /** Sets the shifts found by the latest search; networks whose shift changed are redrawn (main thread). */
    static void update(final Map<Key, Shift> found) {
        final Map<Key, Shift> previous = shifts;
        if (previous.isEmpty() && found.isEmpty()) {
            return;
        }
        shifts = found.isEmpty() ? Map.of() : Map.copyOf(found);
        for (final Map.Entry<Key, Shift> entry : found.entrySet()) {
            if (!entry.getValue().sameAs(previous.get(entry.getKey()))) {
                redraw(entry.getValue().root().get());
            }
        }
        for (final Map.Entry<Key, Shift> entry : previous.entrySet()) {
            if (!found.containsKey(entry.getKey())) {
                redraw(entry.getValue().root().get());
            }
        }
    }

    /** Re-sets the visuals of the network that {@code root} is in. */
    private static void redraw(final KineticBlockEntity root) {
        if (root == null || root.isRemoved() || !(root.getLevel() instanceof ClientLevel level) || root.network == null) {
            return;
        }
        final Set<KineticBlockEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<KineticBlockEntity> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty() && seen.size() < MAX_REDRAWN) {
            for (final KineticBlockEntity next : IPRotationPropagatorInvoker.ip$getConnectedNeighbours(queue.poll())) {
                if (Objects.equals(next.network, root.network) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        // Flywheel serves a world's visualization only while it is Minecraft.level
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel current = minecraft.level;
        minecraft.level = level;
        try {
            for (final KineticBlockEntity be : seen) {
                VisualizationHelper.queueUpdate(be);
            }
        } finally {
            minecraft.level = current;
        }
    }

    /** A fresh map for a search. */
    static Map<Key, Shift> newFound() {
        return new HashMap<>();
    }
}
