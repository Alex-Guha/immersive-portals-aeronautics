/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create pipes through portals under Pipes 'n Physics. Its engine replaces Create's transport (Create's pipe ticks and
 * pump search are cancelled), so none of {@link IPCreatePortalPipes}' hooks run there, and its graph, built within
 * one level, drops a pipe face that meets neither a pipe, a pump, a fluid handler nor an open end: a pipe into a
 * portal was a dead end. Here each portal block face that a pipe meets is a fluid handler, tagged
 * {@code pipesnphysics:relay_endpoint} (data/pipesnphysics/tags/block), the role it gives paired devices such as
 * docking connectors: a bottomless one-way sink while it has nothing to give, a brimming one-way source while it has.
 * <p>
 * Fluid filled in at one end waits in the far end's buffer ({@link #CAPACITY} mB, so a far side that takes nothing
 * backs the near side up) until the far network drains it. Each side is solved on its own, at its own heights: one
 * pump pushes fluid into a portal, and on the far side it flows level or downhill, or a pump there lifts it.
 * Pressure does not cross. Only while the engine is on ({@code enableEngine}); otherwise the face has no handler and
 * Create's own transport and {@link IPCreatePortalPipes} work as without the mod. Frameless portals (no portal blocks)
 * stay dead ends. Buffers are not saved: up to {@link #CAPACITY} mB per pipe end is lost when the server stops.
 * <p>
 * One way at a time: an end the far side fed takes nothing in for {@link #ONE_WAY_TICKS}, and fluid its side leaves
 * undrained for {@link #RETURN_TICKS} goes back to the end it came from. Without the first, fluid that came out above
 * the portal's height flowed straight back in (an empty relay is a sink at the portal's height); without the second,
 * fluid that did get back stuck in a buffer on the pushing side, where a relay holding fluid is a source that a pump
 * pushing into it never drains, and the line stopped. A side that drains fluid sent back to it was flowing back
 * (a pump on the other side still pushes, or would): it takes nothing in for {@link #BACKFLOW_HOLD_TICKS}, so the far
 * tank does not lose and regain a bucket every half minute.
 * <p>
 * Not ours long term: the buffers and the one-way rules make up for Pipes 'n Physics having no coupled relay (both
 * ends of a pair are solved as unrelated sinks and sources), and {@link #engineOn()} reads its config by reflection.
 * Each such part is marked "Upstream (Pipes 'n Physics)" below; what they would add instead is in
 * docs/trackers/upstream-reports.md. Ours for good: the capability on portal block faces, {@link #handlerAt}'s checks and the
 * link to the far pipe ({@link End#far()}).
 */
public final class IPPnpPortalRelay {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPnpRelay] ";
    /** {@code -Dip_aeronautics.debug.portalPipes=true} also logs relay links. */
    private static final boolean DEBUG = Boolean.getBoolean("ip_aeronautics.debug.portalPipes");
    /** What one pipe end holds for the far network to drain, in mB. */
    // Upstream (Pipes 'n Physics): CAPACITY and the holds below go with a coupled relay (docs/trackers/upstream-reports.md).
    static final int CAPACITY = 1000;
    private static final int LINK_REFRESH_TICKS = 20;
    /** Ticks an end takes nothing in after the far side last fed it. */
    private static final int ONE_WAY_TICKS = 600;
    /** Ticks fluid waits undrained in an end before it goes back to the end it came from. */
    private static final int RETURN_TICKS = 100;
    private static final int RETURN_CHECK_TICKS = 20;
    /** Ticks an end takes nothing in after its side drained fluid sent back to it. */
    private static final int BACKFLOW_HOLD_TICKS = 6000;

    private record EndKey(ResourceKey<Level> dimension, long pos, Direction face) {
        static EndKey of(final Level level, final BlockPos pos, final Direction face) {
            return new EndKey(level.dimension(), pos.asLong(), face);
        }
    }

    /** Per pipe end at a portal block: its handler (one object, so the engine sees the same handler each time). */
    private static final Map<EndKey, End> ENDS = new HashMap<>();

    private static @Nullable ModConfigSpec.BooleanValue enableEngine;
    private static boolean engineLookedUp;

    private IPPnpPortalRelay() {
    }

    public static void init(final IEventBus modEventBus) {
        modEventBus.addListener(RegisterCapabilitiesEvent.class, event -> event.registerBlock(
            Capabilities.FluidHandler.BLOCK,
            (level, pos, state, blockEntity, side) -> handlerAt(level, pos, side),
            PortalPlaceholderBlock.instance
        ));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ENDS.clear());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            if (event.getServer().getTickCount() % RETURN_CHECK_TICKS == 0) {
                ENDS.values().forEach(End::returnIfUndrained);
            }
        });
        if (DEBUG) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
                if (event.getServer().getTickCount() % 100 == 0) {
                    ENDS.forEach((key, end) -> LOGGER.info(TAG + "buffer {} {} {}: {} mB {}", key.dimension().location(),
                        BlockPos.of(key.pos()).toShortString(), key.face(), end.buffer.getAmount(),
                        end.buffer.isEmpty() ? "" : end.buffer.getFluid()));
                }
            });
        }
    }

    /**
     * Pipes 'n Physics' {@code enableEngine}; false when it cannot be read (not loaded yet, renamed).
     * Upstream (Pipes 'n Physics): a public engine-enabled query in its {@code api} package would replace the
     * reflection on its config class (docs/trackers/upstream-reports.md).
     */
    private static boolean engineOn() {
        if (!engineLookedUp) {
            engineLookedUp = true;
            try {
                final Field field = Class.forName("de.devin.pipesnphysics.PipesNPhysicsConfig").getField("ENABLE_ENGINE");
                enableEngine = (ModConfigSpec.BooleanValue) field.get(null);
            } catch (final ReflectiveOperationException | ClassCastException e) {
                LOGGER.warn(TAG + "Pipes 'n Physics' enableEngine not found; pipes through portals use Create's transport", e);
            }
        }
        try {
            return enableEngine != null && enableEngine.get();
        } catch (final IllegalStateException e) {
            return false; // server config not loaded yet
        }
    }

    /**
     * The handler of the portal block at {@code pos} on {@code side}, for the pipe end at {@code pos.relative(side)}:
     * only on the server, with the engine on, on a side the portal plane faces, and when that pipe opens towards it
     * (the engine also asks blocks next to pipes running past the portal).
     */
    private static @Nullable IFluidHandler handlerAt(final Level level, final BlockPos pos, final @Nullable Direction side) {
        if (side == null || !(level instanceof ServerLevel serverLevel) || !engineOn()) {
            return null;
        }
        final Direction face = side.getOpposite();
        if (!IPCreatePortalPipes.isPlaceholderFacing(level.getBlockState(pos), face)) {
            return null;
        }
        final BlockPos pipePos = pos.relative(side);
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pipePos);
        if (pipe == null || !pipe.canHaveFlowToward(level.getBlockState(pipePos), face)) {
            return null;
        }
        return ENDS.computeIfAbsent(EndKey.of(level, pipePos, face), k -> new End(serverLevel, pipePos.immutable(), face));
    }

    /**
     * One pipe end at a portal block. Filling it fills the far end's buffer; draining it drains its own, which the far
     * end fills. Upstream (Pipes 'n Physics): with a coupled relay this shrinks to the link ({@link #far()}) and a
     * plain pass-through of fill to the far network; the buffer and everything under "one way at a time" go.
     */
    private static final class End implements IFluidHandler {
        private final ServerLevel level;
        private final BlockPos pipePos;
        private final Direction face;
        private FluidStack buffer = FluidStack.EMPTY;
        // Upstream (Pipes 'n Physics): holdUntil, returned, from and idleSince only serve the one-way rules.
        /** Game time until which this end takes nothing in. */
        private long holdUntil = Long.MIN_VALUE / 2;
        /** Whether {@link #buffer} holds fluid this side sent and got back. */
        private boolean returned;
        /** The end whose side the fluid in {@link #buffer} came from. */
        private @Nullable End from;
        /** Game time {@link #buffer} was last drained or last filled from empty. */
        private long idleSince;
        private IPCreatePortalPipes.@Nullable Link link;
        private boolean linkChecked;
        private long linkCheckedAt;

        End(final ServerLevel level, final BlockPos pipePos, final Direction face) {
            this.level = level;
            this.pipePos = pipePos;
            this.face = face;
        }

        /** After {@code source} put fluid in {@link #buffer}. */
        private void feed(final End source) {
            holdUntil = Math.max(holdUntil, level.getGameTime() + ONE_WAY_TICKS);
            from = source;
        }

        /**
         * Fluid this side left undrained for {@link #RETURN_TICKS} goes back to the end it came from.
         * Upstream (Pipes 'n Physics): the whole method goes with a coupled relay.
         */
        private void returnIfUndrained() {
            final End home = from;
            final long now = level.getGameTime();
            if (buffer.isEmpty() || home == null || now - idleSince < RETURN_TICKS && now >= idleSince) {
                return;
            }
            if (!home.buffer.isEmpty() && !FluidStack.isSameFluidSameComponents(home.buffer, buffer)) {
                return;
            }
            final int amount = Math.min(buffer.getAmount(), CAPACITY - home.buffer.getAmount());
            if (amount <= 0) {
                return;
            }
            if (home.buffer.isEmpty()) {
                home.idleSince = home.level.getGameTime();
            }
            home.buffer = home.buffer.isEmpty() ? buffer.copyWithAmount(amount) : home.buffer.copyWithAmount(home.buffer.getAmount() + amount);
            home.feed(this);
            home.returned = true;
            buffer = amount == buffer.getAmount() ? FluidStack.EMPTY : buffer.copyWithAmount(buffer.getAmount() - amount);
            idleSince = now;
            holdUntil = now; // this side may push again
            returned = false;
            if (DEBUG) {
                LOGGER.info(TAG + "{} mB undrained at {} {} back to {} {}", amount, level.dimension().location(),
                    pipePos.toShortString(), home.level.dimension().location(), home.pipePos.toShortString());
            }
        }

        private @Nullable End far() {
            final long now = level.getGameTime();
            if (!linkChecked || now - linkCheckedAt >= LINK_REFRESH_TICKS || now < linkCheckedAt) {
                final IPCreatePortalPipes.Link found = IPCreatePortalPipes.link(level, pipePos, face);
                if (DEBUG && (found == null) != (link == null)) {
                    LOGGER.info(TAG + "{} {} {}: {}", level.dimension().location(), pipePos.toShortString(), face,
                        found == null ? "no portal" : "linked to " + found.farLevel().dimension().location() + " "
                            + found.farPos().toShortString() + " " + found.farSide());
                }
                link = found;
                linkChecked = true;
                linkCheckedAt = now;
            }
            final IPCreatePortalPipes.Link current = link;
            if (current == null || IPCreatePortalPipes.farPipe(current) == null) {
                return null;
            }
            return ENDS.computeIfAbsent(EndKey.of(current.farLevel(), current.farPos(), current.farSide()),
                k -> new End(current.farLevel(), current.farPos().immutable(), current.farSide()));
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(final int tank) {
            return buffer.copy();
        }

        @Override
        public int getTankCapacity(final int tank) {
            return CAPACITY;
        }

        @Override
        public boolean isFluidValid(final int tank, final @NotNull FluidStack stack) {
            return true;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }
            final long now = level.getGameTime();
            // Upstream (Pipes 'n Physics): this hold goes with a coupled relay.
            if (now < holdUntil && holdUntil - now <= BACKFLOW_HOLD_TICKS) {
                return 0; // fluid comes out here: one way at a time
            }
            final End far = far();
            if (far == null || far == this) {
                return 0;
            }
            if (!far.buffer.isEmpty() && !FluidStack.isSameFluidSameComponents(far.buffer, resource)) {
                return 0;
            }
            final int amount = Math.min(resource.getAmount(), CAPACITY - far.buffer.getAmount());
            if (amount <= 0) {
                return 0;
            }
            if (action.execute()) {
                if (far.buffer.isEmpty()) {
                    far.idleSince = far.level.getGameTime();
                }
                far.buffer = far.buffer.isEmpty() ? resource.copyWithAmount(amount) : far.buffer.copyWithAmount(far.buffer.getAmount() + amount);
                far.feed(this);
            }
            return amount;
        }

        @Override
        public @NotNull FluidStack drain(final FluidStack resource, final FluidAction action) {
            if (buffer.isEmpty() || !FluidStack.isSameFluidSameComponents(buffer, resource)) {
                return FluidStack.EMPTY;
            }
            return drain(resource.getAmount(), action);
        }

        @Override
        public @NotNull FluidStack drain(final int maxDrain, final FluidAction action) {
            final int amount = Math.min(maxDrain, buffer.getAmount());
            if (amount <= 0) {
                return FluidStack.EMPTY;
            }
            final FluidStack drained = buffer.copyWithAmount(amount);
            if (action.execute()) {
                buffer = amount == buffer.getAmount() ? FluidStack.EMPTY : buffer.copyWithAmount(buffer.getAmount() - amount);
                final long now = level.getGameTime();
                idleSince = now;
                if (returned) { // upstream (Pipes 'n Physics): goes with a coupled relay
                    holdUntil = Math.max(holdUntil, now + BACKFLOW_HOLD_TICKS);
                    returned = !buffer.isEmpty();
                }
            }
            return drained;
        }
    }
}
