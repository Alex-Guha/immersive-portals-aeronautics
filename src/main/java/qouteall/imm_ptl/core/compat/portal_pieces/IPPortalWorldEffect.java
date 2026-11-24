/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.portal_pieces;

import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Flywheel pieces of one kind in one client world, as one effect in that world's visualization: pieces that move
 * (half shafts turn) or sit on ships (drawn with the ship's embedding, {@link IPPortalShipFramesClient}), which the
 * chunk mesh cannot hold ({@link IPPortalSectionPieces}). Only loaded with Flywheel.
 */
public abstract class IPPortalWorldEffect implements Effect {
    private final ClientLevel level;
    private WeakReference<VisualizationManagerImpl> manager = new WeakReference<>(null);

    protected IPPortalWorldEffect(final ClientLevel level) {
        this.level = level;
    }

    /**
     * Has Flywheel update the visuals of {@code blockEntities} (all in {@code level}) on its next frame, also when
     * {@code level} is not the player's own world but one seen through a portal: Flywheel serves a world's visualization
     * only while it is {@code Minecraft.level}, and {@code VisualizationHelper.queueUpdate} alone drops the update for
     * any other world. Main thread.
     */
    public static void queueUpdates(final Level level, final Iterable<? extends BlockEntity> blockEntities) {
        if (!(level instanceof final ClientLevel client)) {
            return;
        }
        inLevel(client, () -> {
            for (final BlockEntity blockEntity : blockEntities) {
                VisualizationHelper.queueUpdate(blockEntity);
            }
            return null;
        });
    }

    /** {@link #queueUpdates} for one block entity. */
    public static void queueUpdate(final BlockEntity blockEntity) {
        if (blockEntity.getLevel() != null) {
            queueUpdates(blockEntity.getLevel(), List.of(blockEntity));
        }
    }

    /** {@code action}'s result with {@code level} as {@code Minecraft.level} for its duration. Main thread. */
    private static <T> T inLevel(final ClientLevel level, final Supplier<T> action) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel current = minecraft.level;
        if (current == level) {
            return action.get();
        }
        minecraft.level = level;
        try {
            return action.get();
        } finally {
            minecraft.level = current;
        }
    }

    /**
     * {@code world}'s effect in {@code effects} (weak: client worlds come and go), made by {@code make} the first time
     * it is {@code needed}; null while it is neither there nor needed. Main thread.
     */
    public static <E extends IPPortalWorldEffect> @Nullable E of(
        final Map<ClientLevel, E> effects, final ClientLevel world, final boolean needed, final Function<ClientLevel, E> make
    ) {
        E effect = effects.get(world);
        if (effect == null && needed) {
            effect = make.apply(world);
            effects.put(world, effect);
        }
        return effect;
    }

    public ClientLevel world() {
        return level;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    /**
     * Adds the effect to its world's visualization, again whenever Flywheel made a new one (a backend change), and
     * returns that visualization, or null while there is none. Flywheel serves a world's visualization only while it is
     * {@code Minecraft.level}.
     */
    public @Nullable VisualizationManagerImpl attach() {
        final VisualizationManagerImpl now = inLevel(level, () -> VisualizationManagerImpl.get(level));
        if (now != null && now != manager.get()) {
            manager = new WeakReference<>(now);
            now.effects().queueAdd(this);
        }
        return now;
    }
}
