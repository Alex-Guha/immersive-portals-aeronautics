/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.animation.PortalAnimationDriver;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;

import java.util.List;
import java.util.UUID;

/**
 * Portals mounted on ships (docs/lessons/ship-mounted-portals.md). Entry points for the rest of the mod; only called with
 * Sable present.
 * <p>
 * The switch is the config option {@code shipPortalMode}: off, limited (nether portals break past {@code shipPortalRangeChunks}
 * of link strain, {@link #linkStrain}) or unlimited. {@code -Dip_aeronautics.shipPortals=true|false|limited} overrides it
 * for the JVM; {@code /ip_aeronautics shipportals on|off|limited} changes the option and saves the config file.
 * Off is the behaviour before the feature: no portal can exist on a ship, and a lit portal assembled into a ship breaks
 * ({@code IPShipPortalAssemblyGuard}).
 */
public final class IPShipPortals {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "ip_aeronautics.shipPortals";
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    private IPShipPortals() {
    }

    /**
     * Whether IP may generate a portal from a frame at {@code startPos}. {@code generation} is the IP portal generation
     * (null for the vanilla portal a crouching player lights). From a ship's plot only IP's own nether portal generation
     * may run, and only with the option on: anything else would scale the plot coordinates (millions of blocks) into the
     * other dimension and generate a frame there, and Sable would move the new portal entity out of the plot into the
     * world, where IP breaks the pair at once for abnormal pairing (what happened before 2.2.0, headless
     * {@code shipportal-light.txt}). With the option on, {@link IPShipPortalGeneration} mounts the new portal on the ship.
     */
    public static boolean mayGenerateFrom(final ServerLevel level, final BlockPos startPos, @Nullable final CustomPortalGeneration generation) {
        if (!SABLE || !IPSableCompatHelper.isPlotSpacePosition(startPos.getX(), startPos.getZ())) {
            return true;
        }
        final String refusal;
        if (!isEnabled()) {
            refusal = "portals on ships are switched off";
        } else if (generation == null || generation.identifier == null
                || !generation.identifier.getNamespace().equals("imm_ptl")
                || !generation.identifier.getPath().startsWith("intrinsic_")
                || !generation.identifier.getPath().endsWith("nether_portal")) {
            refusal = "only nether portals can be lit on ships";
        } else {
            refusal = IPShipPortalGeneration.whyNotOnShip(level, startPos);
        }
        if (refusal == null) {
            return true;
        }
        LOGGER.info("[IPShipPortal] not generating a portal from a ship at plot {} in {}: {}",
                startPos.toShortString(), level.dimension().location(), refusal);
        return false;
    }

    /**
     * IP maps the frame's position into the other dimension to search for or place the other side: for a frame on a
     * ship, the ship's world position instead of its plot position.
     */
    public static BlockPos positionForMapping(final ServerLevel level, final BlockPos from) {
        if (!SABLE || !IPSableCompatHelper.isPlotSpacePosition(from.getX(), from.getZ())) {
            return from;
        }
        return IPShipPortalGeneration.worldPositionOf(level, from);
    }

    /**
     * IP created the first portal of a new pair ({@code f1}, from {@code fromShape}), before deriving the other three
     * from it. For a frame on a ship, puts it at the ship's world pose and returns what {@link #afterPairSpawned} needs;
     * null otherwise.
     */
    @Nullable
    public static Object beforePairDerived(final BreakablePortalEntity f1, final BlockPortalShape fromShape) {
        if (!SABLE || !IPSableCompatHelper.isPlotSpacePosition(fromShape.anchor.getX(), fromShape.anchor.getZ())) {
            return null;
        }
        return IPShipPortalGeneration.placeOnShip(f1, fromShape);
    }

    /** IP spawned the new pair: mounts its ship side on the ship ({@code token} from {@link #beforePairDerived}). */
    public static void afterPairSpawned(final BreakablePortalEntity f1, @Nullable final Object token) {
        if (token != null) {
            IPShipPortalGeneration.mount(f1, token);
        }
    }

    /**
     * True for a ship-side portal (its block shape is in a ship's plot) whose ship is not loaded: its blocks cannot be
     * read, so its frame cannot be judged.
     */
    public static boolean isOnUnloadedShip(final BreakablePortalEntity portal) {
        final BlockPortalShape shape = portal.blockPortalShape;
        if (!SABLE || shape == null || !IPSableCompatHelper.isPlotSpacePosition(shape.anchor.getX(), shape.anchor.getZ())) {
            return false;
        }
        return IPShipPortalGeneration.whyNotOnShip((ServerLevel) portal.level(), shape.anchor) != null;
    }

    /** The ship mount of this portal's own animation (the cluster's holder on the ship side), or null. */
    @Nullable
    public static IPShipMount mountOf(final Portal portal) {
        return mountIn(portal.animation.thisSideAnimations);
    }

    /**
     * The ship mount of the other side of this portal's animation, or null. A holder has one when the far end of its
     * cluster is on a ship too (docs/lessons/ship-mounted-portals.md): that ship moves the holder's destination.
     */
    @Nullable
    public static IPShipMount otherSideMountOf(final Portal portal) {
        return mountIn(portal.animation.otherSideAnimations);
    }

    @Nullable
    private static IPShipMount mountIn(final List<PortalAnimationDriver> drivers) {
        for (final PortalAnimationDriver driver : drivers) {
            if (driver instanceof final IPShipMount mount) {
                return mount;
            }
        }
        return null;
    }

    /**
     * The ship mount of the portal's cluster (any of its four portals, either side), or null. With a ship at each end it
     * is the holder's own end's; {@link #ownSideCarrier} and {@link #farSideCarrier} tell the sides apart.
     */
    @Nullable
    public static IPShipMount clusterMountOf(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        if (holder == null) {
            return null;
        }
        final IPShipMount mount = mountOf(holder);
        return mount != null ? mount : otherSideMountOf(holder);
    }

    /**
     * True for every portal of a ship-mounted cluster, on both sides: the ship side moves, and so does the far side's
     * destination. Server and client.
     */
    public static boolean isShipMounted(final Portal portal) {
        return clusterMountOf(portal) != null;
    }

    /** The ship carrying {@code portal}'s own side, or null when that side is fixed in the world. Server and client. */
    @Nullable
    public static UUID ownSideCarrier(final Portal portal) {
        return sideCarrier(portal, true);
    }

    /** The ship carrying the side {@code portal} leads to, or null when that side is fixed in the world. Server and client. */
    @Nullable
    public static UUID farSideCarrier(final Portal portal) {
        return sideCarrier(portal, false);
    }

    @Nullable
    private static UUID sideCarrier(final Portal portal, final boolean own) {
        final IPShipMount mount = sideMount(portal, own);
        return mount == null ? null : mount.carrier();
    }

    /** The mount of {@code portal}'s own side, or null when that side is fixed in the world. Server and client. */
    @Nullable
    public static IPShipMount ownSideMount(final Portal portal) {
        return sideMount(portal, true);
    }

    @Nullable
    private static IPShipMount sideMount(final Portal portal, final boolean own) {
        final Portal holder = portal.getAnimationHolder();
        if (holder == null) {
            return null;
        }
        return isOnHolderSide(portal, holder) == own ? mountOf(holder) : otherSideMountOf(holder);
    }

    /**
     * Whether {@code portal} is on the holder's own side of the cluster whose animation {@code holder} holds: the holder
     * and its flipped portal (the reverse and parallel portals are the other side). Not by level: with both sides in
     * one dimension all four are in one level.
     */
    static boolean isOnHolderSide(final Portal portal, final Portal holder) {
        return portal == holder || PortalExtension.get(holder).flippedPortal == portal
                || PortalExtension.get(portal).flippedPortal == holder;
    }

    public static boolean isEnabled() {
        return mode() != IPGlobal.ShipPortalMode.off;
    }

    public static IPGlobal.ShipPortalMode mode() {
        final IPGlobal.ShipPortalMode forced = propertyOverride();
        return forced != null ? forced : IPConfig.getConfig().shipPortalMode;
    }

    /** @return the mode the JVM property asks for (true: unlimited, false: off, limited), null when it does not */
    public static IPGlobal.ShipPortalMode propertyOverride() {
        final String value = System.getProperty(ENABLE_PROPERTY);
        if ("true".equalsIgnoreCase(value)) {
            return IPGlobal.ShipPortalMode.unlimited;
        }
        if ("false".equalsIgnoreCase(value)) {
            return IPGlobal.ShipPortalMode.off;
        }
        if ("limited".equalsIgnoreCase(value)) {
            return IPGlobal.ShipPortalMode.limited;
        }
        return null;
    }

    /**
     * Changes the config option and saves the config file. With the JVM property set, the property still decides.
     */
    public static void setMode(final IPGlobal.ShipPortalMode mode) {
        final IPConfig config = IPConfig.getConfig();
        if (config.shipPortalMode == mode) {
            return;
        }
        config.shipPortalMode = mode;
        config.saveConfigFile();
        LOGGER.info("[IPShipPortal] ship-mounted portals switched to {} in the config{}", mode,
                propertyOverride() != null ? " (overridden by -D" + ENABLE_PROPERTY + ")" : "");
    }

    /** Changes the range of the limited mode, in chunks, and saves the config file. */
    public static void setRange(final int range) {
        final IPConfig config = IPConfig.getConfig();
        if (config.shipPortalRangeChunks != range) {
            config.shipPortalRangeChunks = range;
            config.saveConfigFile();
        }
    }

    /**
     * How far {@code portal}'s link has moved from a local one, in overworld chunks: the horizontal distance between its
     * two ends, each at its dimension's coordinate scale. Worked out from the ends each time, for one end or two on ships:
     * a saved starting point could be reset by disassembling and reassembling (docs/lessons/ship-mounted-portals.md).
     */
    public static double linkStrain(final Portal portal) {
        final Vec3 origin = portal.getOriginPos();
        final Vec3 dest = portal.getDestPos();
        final double originScale = portal.level().dimensionType().coordinateScale();
        final double destScale = portal.getDestWorld().dimensionType().coordinateScale();
        return Math.hypot(origin.x * originScale - dest.x * destScale, origin.z * originScale - dest.z * destScale) / 16;
    }

    /** Whether the limited mode applies to {@code portal}: nether portals only, custom and datapack ones keep no ratio. */
    public static boolean hasRangeLimit(final Portal portal) {
        return portal instanceof NetherPortalEntity && mode() == IPGlobal.ShipPortalMode.limited;
    }
}
