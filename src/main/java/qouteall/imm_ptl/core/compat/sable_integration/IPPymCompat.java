/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Pym (bundled with Create: Pocket Sized) scales ships, physics included, through Sable's own Rapier classes, so it
 * runs on our physics library too, and writes the scale into the ship's pose, which our straddle code applies
 * (docs/lessons/scaled-ships.md). A ship Pym is resizing does not straddle: its shape changes under the session, so
 * it keeps the one-step crossing until it settles. Read through Pym's public API ({@code Pym.scale().isScaled},
 * {@code isSettled}) by reflection, so we build without Pym.
 */
public final class IPPymCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "pym";

    private static volatile boolean resolved;
    @Nullable
    private static Object scaleQuery;
    @Nullable
    private static Method isScaled;
    @Nullable
    private static Method isSettled;
    @Nullable
    private static volatile String problem;
    private static volatile boolean warnedCall;
    private static volatile boolean resolvedLifecycles;
    @Nullable
    private static Method isHosted;
    @Nullable
    private static Method isDetached;
    @Nullable
    private static Method findDetached;
    private static volatile boolean warnedLifecycle;

    private IPPymCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * @return null when Pym is absent or its scale query is readable; otherwise why it is not (a Pym version whose API
     * moved), in which case ships cannot be told apart and our physics library must not run
     */
    @Nullable
    public static String queryProblem() {
        if (!isLoaded()) {
            return null;
        }
        resolve();
        return problem;
    }

    /** @return whether Pym has the ship at a scale other than 1 (false without Pym) */
    public static boolean isScaled(final SubLevel ship) {
        return query(false, ship);
    }

    /** @return whether Pym is changing the ship's scale (false without Pym) */
    public static boolean isResizing(final SubLevel ship) {
        return !query(true, ship);
    }

    /**
     * Whether Pym has the ship worn (its {@code Hosted} lifecycle, from Pym 0.7: Create: Pocket Sized's hats and the ships
     * riding them): its physics body is pinned under the world while its pose follows the wearer's head, Pym sends it to
     * no client, and Pocket moves it to the wearer's dimension itself. False without Pym 0.7.
     */
    public static boolean isWorn(final SubLevel ship) {
        return lifecycle(true, ship);
    }

    /**
     * Whether the ship is one of Pym's builds rather than a ship of the world: worn ({@link #isWorn}), or detached
     * ({@code Detached}: kept out of physics, such as a build on a Pocket hat stand). Those neither cross nor straddle
     * portals, nor carry them. False without Pym 0.7.
     */
    public static boolean isHeldBuild(final SubLevel ship) {
        return lifecycle(true, ship) || lifecycle(false, ship);
    }

    /**
     * Whether Pym holds a detached ship with this UUID ({@code Detached.find}): detached ships are kept out of Sable's
     * UUID lookup, so a ship that is not found may still be one. False without Pym 0.7.
     */
    public static boolean isDetachedId(final java.util.UUID ship) {
        if (!isLoaded()) {
            return false;
        }
        resolveLifecycles();
        if (findDetached == null) {
            return false;
        }
        try {
            return findDetached.invoke(null, ship) != null;
        } catch (final Throwable t) {
            if (!warnedLifecycle) {
                warnedLifecycle = true;
                LOGGER.warn("[IPShipPortal] could not ask Pym whether a ship is worn or detached (logged once)", t);
            }
            return false;
        }
    }

    private static boolean lifecycle(final boolean worn, final SubLevel ship) {
        if (!isLoaded()) {
            return false;
        }
        resolveLifecycles();
        final Method method = worn ? isHosted : isDetached;
        if (method == null) {
            return false;
        }
        try {
            return (boolean) method.invoke(null, ship);
        } catch (final Throwable t) {
            if (!warnedLifecycle) {
                warnedLifecycle = true;
                LOGGER.warn("[IPShipPortal] could not ask Pym whether a ship is worn or detached (logged once)", t);
            }
            return false;
        }
    }

    private static synchronized void resolveLifecycles() {
        if (resolvedLifecycles) {
            return;
        }
        resolvedLifecycles = true;
        try {
            final ClassLoader loader = IPPymCompat.class.getClassLoader();
            isHosted = Class.forName("com.misterblusky9.pym.api.Hosted", true, loader).getMethod("isHosted", SubLevel.class);
            final Class<?> detached = Class.forName("com.misterblusky9.pym.api.Detached", true, loader);
            isDetached = detached.getMethod("isDetached", SubLevel.class);
            findDetached = detached.getMethod("find", java.util.UUID.class);
        } catch (final ReflectiveOperationException | LinkageError t) {
            isHosted = null;
            isDetached = null;
            findDetached = null;
            // Pym before 0.7 has neither lifecycle: every ship is a ship of the world
            LOGGER.info("[IPShipPortal] this Pym has no worn or detached ships (Hosted.isHosted, Detached.isDetached: before Pym 0.7)");
        }
    }

    /** @return {@code isSettled} (true when unknown) or {@code isScaled} (false when unknown) of Pym's scale query */
    private static boolean query(final boolean settled, final SubLevel ship) {
        if (!isLoaded()) {
            return settled;
        }
        resolve();
        final Method method = settled ? isSettled : isScaled;
        if (method == null) {
            return settled;
        }
        try {
            return (boolean) method.invoke(scaleQuery, ship);
        } catch (final Throwable t) {
            if (!warnedCall) {
                warnedCall = true;
                LOGGER.warn("[IPStraddle] could not read a ship's scale from Pym (logged once)", t);
            }
            return settled;
        }
    }

    /**
     * Pym's answer to a resize: accepted, or refused with a status ({@code ResizeResult.Status}: {@code BLOCKED},
     * {@code OUT_OF_BOUNDS}, {@code LIMITED}, {@code INVALID_SCALE}, {@code UNAVAILABLE}, {@code UNCONFIRMED}).
     */
    public record Resize(boolean accepted, String status, String description, double scale) {
        /** Accepted at exactly {@code wanted} (Pym clamps a request to its bounds and still accepts it). */
        public boolean acceptedAt(final double wanted) {
            return this.accepted && Math.abs(this.scale - wanted) <= 1.0e-6;
        }
    }

    /**
     * Whether Pym would resize {@code ship} to {@code scale} ({@code ResizeRequest.check}; its clearance test is where
     * the ship is now). Null without Pym or when its resize API is not there.
     */
    @Nullable
    public static Resize checkResize(final ServerSubLevel ship, final double scale) {
        return resizeCall(ship, scale, null, false);
    }

    /**
     * Why Pym would not let {@code ship} become {@code toScale} through a portal, or null when it would. Pym's clearance
     * where the ship is now ({@code BLOCKED}) does not count: the far side is judged by the crossing itself.
     */
    @Nullable
    public static String whyNotResizable(final ServerSubLevel ship, final double toScale) {
        final Resize check = checkResize(ship, toScale);
        if (check == null) {
            return "Pym's resize API is not there";
        }
        if (check.accepted()) {
            return check.acceptedAt(toScale) ? null : "Pym allows " + String.format("%.3f", check.scale()) + " at most";
        }
        return "BLOCKED".equals(check.status()) ? null : check.status() + ": " + check.description();
    }

    /** Resizes {@code ship} to {@code scale} at once about {@code anchor} (Pym's {@code immediate}). Null as above. */
    @Nullable
    public static Resize resizeNow(final ServerSubLevel ship, final double scale, final Vector3dc anchor) {
        return resizeCall(ship, scale, anchor, true);
    }

    @Nullable
    private static Resize resizeCall(final ServerSubLevel ship, final double scale, @Nullable final Vector3dc anchor,
                                     final boolean submit) {
        if (!isLoaded()) {
            return null;
        }
        try {
            final Class<?> pym = Class.forName("com.misterblusky9.pym.api.Pym", true, IPPymCompat.class.getClassLoader());
            final Object resizing = pym.getMethod("resize").invoke(null);
            Object request = resizing.getClass().getMethod("request", ServerSubLevel.class).invoke(resizing, ship);
            final Class<?> requestClass = request.getClass();
            request = requestClass.getMethod("scaleTo", double.class).invoke(request, scale);
            // the range a player is held to: Pym's supported scales, or what it can reach when the server allows more
            final Class<?> resizingClass = resizing.getClass();
            final boolean unsupportedAllowed = (boolean) resizingClass.getMethod("unsupportedAllowed").invoke(resizing);
            final Object bounds = resizingClass.getMethod(unsupportedAllowed ? "reachable" : "supported").invoke(resizing);
            request = requestClass.getMethod("bounds", bounds.getClass()).invoke(request, bounds);
            if (anchor != null) {
                request = requestClass.getMethod("anchor", Vector3dc.class).invoke(request, anchor);
            }
            if (submit) {
                request = requestClass.getMethod("immediate").invoke(request);
            }
            final Object result = requestClass.getMethod(submit ? "submit" : "check").invoke(request);
            final Class<?> resultClass = result.getClass();
            final boolean accepted = (boolean) resultClass.getMethod("accepted").invoke(result);
            final Object status = resultClass.getMethod("status").invoke(result);
            final Object description = resultClass.getMethod("describe").invoke(result);
            final double resultScale = (double) resultClass.getMethod("scale").invoke(result);
            return new Resize(accepted, String.valueOf(status), String.valueOf(description), resultScale);
        } catch (final Throwable t) {
            if (!warnedCall) {
                warnedCall = true;
                LOGGER.warn("[IPShipPortal] could not call Pym's resize API (Pym.resize().request ...; logged once)", t);
            }
            return null;
        }
    }

    private static boolean warnedForget;

    /**
     * Makes Pym forget the joints it recorded on {@code ship} ({@code ConstraintRefresh.TRACKED}, by body UUID). Pym
     * rebuilds a recorded joint whose anchors would move when a body is resized ({@code ConstraintRefresh.correct},
     * finding the body by UUID in the resized body's level). A ship moved to another level keeps its UUID, so after a
     * scaled portal's hand-off the last joint a handle had on the old copy was rebuilt on the new one, aimed at the old
     * world's coordinates, owned by nobody: the ship flew into the sky or the ground and stayed.
     * Call once the old copy is gone; joints meant to exist after the move are made again by their owners.
     *
     * @return how many records were dropped (0 without Pym or when its internals are not there)
     */
    public static int forgetConstraintsOf(final java.util.UUID ship) {
        if (!isLoaded()) {
            return 0;
        }
        try {
            final Class<?> refresh = Class.forName("com.misterblusky9.pym.internal.physics.ConstraintRefresh", true,
                IPPymCompat.class.getClassLoader());
            final Class<?> tracked = Class.forName("com.misterblusky9.pym.internal.physics.ConstraintRefresh$Tracked", true,
                IPPymCompat.class.getClassLoader());
            final java.lang.reflect.Method bodyA = tracked.getDeclaredMethod("bodyA");
            final java.lang.reflect.Method bodyB = tracked.getDeclaredMethod("bodyB");
            bodyA.setAccessible(true);
            bodyB.setAccessible(true);
            int dropped = 0;
            for (final String name : new String[]{"TRACKED", "OUTDATED"}) {
                final java.lang.reflect.Field field = refresh.getDeclaredField(name);
                field.setAccessible(true);
                final java.util.List<?> list = (java.util.List<?>) field.get(null);
                for (final java.util.Iterator<?> it = list.iterator(); it.hasNext(); ) {
                    final Object entry = it.next();
                    if (ship.equals(bodyA.invoke(entry)) || ship.equals(bodyB.invoke(entry))) {
                        it.remove();
                        dropped++;
                    }
                }
            }
            return dropped;
        } catch (final Throwable t) {
            if (!warnedForget) {
                warnedForget = true;
                LOGGER.warn("[IPShipPortal] could not reach Pym's joint records (ConstraintRefresh.TRACKED; logged once): {}", t.toString());
            }
            return 0;
        }
    }

    /**
     * Test hook: leases {@code ship} to a Pym scale driver that holds it at {@code scale} until {@code ticks} from now
     * ({@code Pym.resize().drive}), as another mod built on Pym would (a driver is kept by the ship's UUID).
     *
     * @return null when leased, otherwise why not
     */
    @Nullable
    public static String debugDrive(final ServerSubLevel ship, final double scale, final long ticks) {
        if (!isLoaded()) {
            return "Pym is not loaded";
        }
        try {
            final ClassLoader loader = IPPymCompat.class.getClassLoader();
            final Class<?> driverClass = Class.forName("com.misterblusky9.pym.api.ScaleDriver", true, loader);
            final Object driver = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{driverClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commandedScale" -> scale;
                    case "isRemoved" -> false;
                    case "toString" -> "ip_aeronautics debug driver at " + scale;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                });
            final Class<?> pym = Class.forName("com.misterblusky9.pym.api.Pym", true, loader);
            final Object resizing = pym.getMethod("resize").invoke(null);
            resizing.getClass().getMethod("drive", ServerSubLevel.class, driverClass, long.class)
                .invoke(resizing, ship, driver, ship.getLevel().getGameTime() + ticks);
            return null;
        } catch (final Throwable t) {
            return t.toString();
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            final Class<?> pym = Class.forName("com.misterblusky9.pym.api.Pym", true, IPPymCompat.class.getClassLoader());
            final Object query = pym.getMethod("scale").invoke(null);
            isScaled = query.getClass().getMethod("isScaled", SubLevel.class);
            isSettled = query.getClass().getMethod("isSettled", SubLevel.class);
            scaleQuery = query;
        } catch (final Throwable t) {
            problem = t.toString();
            LOGGER.warn("[IPStraddle] Pym's scale query (Pym.scale().isScaled, isSettled) is not there: {}", problem);
        }
    }
}
