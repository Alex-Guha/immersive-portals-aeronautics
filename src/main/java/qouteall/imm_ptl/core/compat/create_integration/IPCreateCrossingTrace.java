/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Client diagnostics, written for BUGS #5 (2.3.7; user: watched from the entry side, the front of a carriage flashed
 * ahead of the train as its front wheels reached the portal; it caught the cause, fixed in 2.3.8). One line per frame and carriage
 * while the carriage has copies in two dimensions or a crossing: each copy's state, where Flywheel placed it this
 * frame (per level frame start, with that start's partial tick), what each Flywheel pass did with its embeddings
 * (cut, hidden, drawn whole, far half) and with its vanilla-drawn parts, and how far a copy of another dimension,
 * mapped through the portal, stands from the reference copy (the one in the player's dimension).
 * Off unless {@code -Dip_aeronautics.debug.trainTrace=true} (2.3.9).
 */
public final class IPCreateCrossingTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final boolean ENABLED = Boolean.getBoolean("ip_aeronautics.debug.trainTrace");
    private static final int MAX_LINES = 15000;
    private static int failures;

    /** This frame's draws per copy: "pass result" -> count. */
    private static final Map<CarriageContraptionEntity, Map<String, int[]>> DRAWS = new IdentityHashMap<>();
    /** This frame's Flywheel placements per copy. */
    private static final Map<CarriageContraptionEntity, List<String>> EMBEDDED = new IdentityHashMap<>();
    /** The last gap shift applied to each copy. */
    private static final Map<CarriageContraptionEntity, Vec3> SHIFTS = new WeakHashMap<>();
    private static final List<String> PASSES = new ArrayList<>();
    private static String pass = "?";
    private static int lines;
    private static long frame;

    private IPCreateCrossingTrace() {
    }

    /** Start of a Flywheel pass of a level (or of a far-half draw of its engine). */
    public static void beginPass(final Level level, final boolean farHalf) {
        if (!ENABLED) {
            return;
        }
        pass = (farHalf ? "farHalf:" : PortalRendering.isRendering() ? "portal:" : "direct:") + dim(level.dimension());
        PASSES.add(pass);
    }

    /** What a Flywheel pass does with one embedding of a copy. */
    static void plane(@Nullable final CarriageContraptionEntity entity, final float @Nullable [] plane) {
        if (!ENABLED || entity == null) {
            return;
        }
        final String kind = plane == null ? "WHOLE"
            : plane[0] == 0f && plane[1] == 0f && plane[2] == 0f ? (plane[3] < 0f ? "hidden" : "WHOLE") : "cut";
        count(entity, pass + " " + kind);
    }

    /** A part of a copy that Create draws with its vanilla renderer. */
    static void vanilla(final CarriageContraptionEntity entity, final boolean drawn) {
        if (ENABLED) {
            count(entity, (PortalRendering.isRendering() ? "vanilla(portal) " : "vanilla(direct) ") + (drawn ? "DRAWN" : "skipped"));
        }
    }

    private static void count(final CarriageContraptionEntity entity, final String key) {
        DRAWS.computeIfAbsent(entity, e -> new LinkedHashMap<>()).computeIfAbsent(key, k -> new int[1])[0]++;
    }

    /** A contraption visual's frame start: where Flywheel places the copy (as Create's setEmbeddingMatrices does). */
    public static void embedded(@Nullable final AbstractContraptionEntity entity, final float partialTick) {
        if (!ENABLED || !(entity instanceof final CarriageContraptionEntity carriage)) {
            return;
        }
        final Vec3 position = entity.isPrevPosInvalid() ? entity.position() : lerp(entity, partialTick);
        EMBEDDED.computeIfAbsent(carriage, e -> new ArrayList<>())
            .add(fmt(position) + (entity.isPrevPosInvalid() ? " (prev invalid)" : "") + " pt " + String.format("%.3f", partialTick));
    }

    public static void shift(final CarriageContraptionEntity entity, final Vec3 shift) {
        if (ENABLED && entity.level().isClientSide()) {
            SHIFTS.put(entity, shift);
        }
    }

    /** After each frame. */
    public static void afterFrame() {
        if (!ENABLED) {
            return;
        }
        frame++;
        try {
            log();
        } catch (final Throwable t) {
            // a frame that cannot be described is skipped (2.5.7.4 turned the trace off at the first one)
            if (failures++ < 3) {
                LOGGER.warn("[IPTrainTrace] frame {} not traced", frame, t);
            }
        } finally {
            DRAWS.clear();
            EMBEDDED.clear();
            PASSES.clear();
        }
    }

    private static void log() {
        final Minecraft client = Minecraft.getInstance();
        if (lines >= MAX_LINES || client.level == null) {
            return;
        }
        final float partialTick = client.getTimer().getGameTimeDeltaPartialTick(false);
        final Map<Object, List<CarriageContraptionEntity>> groups = new LinkedHashMap<>();
        for (final CarriageContraptionEntity entity : IPCreateCarriageCrossingClient.knownCopies()) {
            if (entity.isRemoved()) {
                continue;
            }
            final Object key = entity.getCarriage() != null ? entity.getCarriage() : entity;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (final Map.Entry<Object, List<CarriageContraptionEntity>> group : groups.entrySet()) {
            final List<CarriageContraptionEntity> copies = group.getValue();
            boolean interesting = copies.size() >= 2 || !(group.getKey() instanceof Carriage);
            for (final CarriageContraptionEntity copy : copies) {
                interesting |= IPCreateCarriageCrossingClient.crossingOf(copy) != null;
            }
            if (!interesting) {
                continue;
            }
            CarriageContraptionEntity reference = copies.getFirst();
            for (final CarriageContraptionEntity copy : copies) {
                if (copy.level() == client.level && copy.getCarriage() != null
                    && IPCreateCarriageCrossing.hasPointIn(copy.getCarriage(), IPCreateTrainSides.keyOf(copy))) {
                    reference = copy;
                }
            }
            final Vec3 referencePosition = lerp(reference, partialTick);
            final StringBuilder text = new StringBuilder();
            text.append("[IPTrainTrace] frame ").append(frame).append(" pt ").append(String.format("%.3f", partialTick))
                .append(" in ").append(dim(client.level.dimension())).append(" passes ").append(PASSES);
            if (group.getKey() instanceof final Carriage carriage) {
                text.append(" carriage ").append(carriage.id);
            } else {
                text.append(" UNBOUND copy");
            }
            for (final CarriageContraptionEntity copy : copies) {
                final Vec3 position = lerp(copy, partialTick);
                text.append("\n    #").append(copy.getId()).append(copy == reference ? " (ref) " : " ")
                    .append(dim(copy.level().dimension()))
                    .append(IPCreateTrainSides.isSideCopy(copy)
                        ? " side " + fmt(IPCreateTrainSides.face(IPCreateTrainSides.keyOf(copy)).getLocation()) : "")
                    .append(" tick ").append(copy.level().getGameTime())
                    .append('/').append(copy.tickCount)
                    .append(" [").append(IPCreateCarriageCrossingClient.traceState(copy)).append(']')
                    .append(" pos ").append(fmt(position)).append(" x ").append(fmt(copy.position()));
                final Vec3 shift = SHIFTS.get(copy);
                if (shift != null) {
                    text.append(" shift ").append(String.format("%.3f", shift.length()));
                }
                if (copy != reference) {
                    final Vec3 mapped = mapInto(copy, position, reference);
                    if (mapped != null) {
                        final Vec3 delta = mapped.subtract(referencePosition);
                        text.append(" vsRef ").append(fmt(delta)).append(String.format(" |%.3f|", delta.length()));
                    }
                }
                text.append("\n      flywheel ").append(EMBEDDED.getOrDefault(copy, List.of()))
                    .append(" draws ").append(drawsText(copy));
            }
            LOGGER.info(text.toString());
            if (++lines >= MAX_LINES) {
                LOGGER.info("[IPTrainTrace] line limit reached, trace stops");
                return;
            }
        }
    }

    private static String drawsText(final CarriageContraptionEntity copy) {
        final Map<String, int[]> draws = DRAWS.get(copy);
        if (draws == null) {
            return "{}";
        }
        final StringBuilder text = new StringBuilder("{");
        for (final Map.Entry<String, int[]> entry : draws.entrySet()) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(entry.getKey()).append(" x").append(entry.getValue()[0]);
        }
        return text.append('}').toString();
    }

    /** A copy's position mapped into the reference copy's dimension through the portal of either one's crossing. */
    @Nullable
    private static Vec3 mapInto(final CarriageContraptionEntity copy, final Vec3 position, final CarriageContraptionEntity reference) {
        final ResourceKey<Level> copyKey = IPCreateTrainSides.keyOf(copy);
        final ResourceKey<Level> referenceKey = IPCreateTrainSides.keyOf(reference);
        if (copyKey.equals(referenceKey)) {
            return position;
        }
        final IPCreateCarriageCrossing.Crossing own = IPCreateCarriageCrossingClient.crossingOf(copy);
        if (own != null && own.otherKey().equals(referenceKey)) {
            return own.portal().transformPoint(position);
        }
        final IPCreateCarriageCrossing.Crossing ref = IPCreateCarriageCrossingClient.crossingOf(reference);
        if (ref != null && ref.otherKey().equals(copyKey)) {
            final Portal portal = ref.portal();
            return portal.inverseTransformPoint(position);
        }
        return null;
    }

    private static Vec3 lerp(final AbstractContraptionEntity entity, final float partialTick) {
        return new Vec3(Mth.lerp(partialTick, entity.xo, entity.getX()), Mth.lerp(partialTick, entity.yo, entity.getY()),
            Mth.lerp(partialTick, entity.zo, entity.getZ()));
    }

    private static String dim(final ResourceKey<Level> dimension) {
        return dimension.location().getPath();
    }

    static String fmt(final Vec3 v) {
        return String.format("(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }
}
