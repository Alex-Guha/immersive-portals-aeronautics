/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Portal frame rate target (client option {@code portalFrameRateTarget}, 0 = off): every portal view is a whole second
 * world render, and with a shader pack the pack's whole pipeline, so their cost adds up with each portal seen through
 * another. While the frame rate with portals in view stays below the target, views are dropped in steps, the least
 * visible first, and given back one step at a time once it recovers:
 * <ol>
 * <li>nested views (a portal seen through a portal) covering less than 2 % of the screen;</li>
 * <li>nested views under 10 %;</li>
 * <li>all nested views;</li>
 * <li>also direct views under a quarter of a percent (a small, distant portal).</li>
 * </ol>
 * A dropped view looks like one past the portal layer limit: what is behind the portal shows through the nether portal
 * texture ({@code ViewAreaRenderer.renderNotDrawnViewFill}). A view's size is its portal's
 * rectangle on screen, cut to the rectangle of the view it is seen through.
 * <p>
 * Giving a step back that makes the frame rate fall again doubles the wait before that step is tried again (2 to 30
 * seconds; each step its own, reset once it holds), so a scene that sits on the target does not flicker between
 * steps, and the other steps still come back after 2 seconds. The frame rate is the median frame interval of the
 * last 1.5 seconds, so a hitch (a crossing, chunk loading, a garbage collection) does not count; with vsync at the
 * target it reads the target and nothing is dropped. Nothing changes for 3 seconds after the world changes (joining,
 * crossing into another dimension) or a frame over a quarter second, and a step given back is judged 2 seconds later,
 * after the returning views' first frames.
 * <p>
 * The last step, {@link #NEARBY_ONLY} (once the option Lag Attack Proof), is a safety net for rooms full of mirrors
 * or portals: with more than 10 views in the last frame (drawn or dropped) and each of the last 3 seconds under
 * 8 fps, it is taken at once from any step. Only portals within 16 blocks are drawn then, none seen through another
 * and without the fade ({@code PortalRenderer.getRenderRange}, {@code PortalRendering.getMaxPortalLayer},
 * {@code IPPortalDrawDistance}), with a message on screen; it goes back to step 4 once none of the last 20 seconds
 * was at 15 fps or under. It judges {@link ClientPerformanceMonitor}'s whole seconds, not the median: at a few fps
 * every frame is a hitch to the median. Target 0 turns it off with the rest.
 */
public final class IPViewGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The last step of the ladder the median frame rate walks. */
    private static final int MAX_LEVEL = 4;
    /** The safety net past the ladder: only portals within 16 blocks, none nested. */
    private static final int NEARBY_ONLY = 5;
    private static final int NEARBY_ONLY_MIN_VIEWS = 10;
    private static final int NEARBY_ONLY_BELOW_FPS = 8;
    private static final int NEARBY_ONLY_SECONDS = 3;
    private static final int NEARBY_ONLY_LEAVE_ABOVE_FPS = 15;
    /** Per level, the screen fraction under which a nested view (layer 2 and deeper) is dropped. */
    private static final double[] NESTED_MIN_AREA =
        {0, 0.02, 0.10, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    /** Per level, the screen fraction under which a direct view (layer 1) is dropped. */
    private static final double[] DIRECT_MIN_AREA = {0, 0, 0, 0, 0.0025, 0.0025};

    private static final double DROP_BELOW = 0.9;
    private static final double RESTORE_FROM = 0.97;
    private static final long WINDOW_NANOS = 1_500_000_000L;
    private static final int WINDOW_FRAMES = 1024;
    private static final double HITCH_SECONDS = 0.25;
    private static final long SETTLE_NANOS = 3_000_000_000L;
    private static final long RESTORE_GRACE_NANOS = 2_000_000_000L;
    private static final long STEP_NANOS = 750_000_000L;
    private static final long FIRST_RESTORE_DELAY_NANOS = 2_000_000_000L;
    private static final long MAX_RESTORE_DELAY_NANOS = 30_000_000_000L;
    /** A step given back counts as failed when the next drop comes this soon after it. */
    private static final long FAILED_RESTORE_NANOS = 2_000_000_000L;
    /** With no views for this long, the next portal starts with every view. */
    private static final long RESET_WITHOUT_VIEWS_NANOS = 5_000_000_000L;

    private static int level = 0;
    private static int lastTarget = -1;
    private static double frameSeconds = 1.0 / 60;
    /** Frame intervals (seconds) and when they ended, a ring of the last {@link #WINDOW_FRAMES}. */
    private static final double[] intervals = new double[WINDOW_FRAMES];
    private static final long[] ends = new long[WINDOW_FRAMES];
    private static int ringNext = 0;
    private static int ringSize = 0;
    private static long settleUntilNanos = 0;
    /** Until when the world just changed: no {@link #NEARBY_ONLY} while chunks and pipelines load. */
    private static long levelSettleUntilNanos = 0;
    private static @org.jetbrains.annotations.Nullable Object lastLevel = null;
    private static long lastFrameNanos = 0;
    private static long lastChangeNanos = 0;
    private static long lastRestoreNanos = 0;
    private static long lastViewsNanos = 0;
    /** Per step, the wait before giving it back: doubled each time giving it back fails, reset once it holds. */
    private static final long[] restoreDelayNanos = new long[MAX_LEVEL];

    static {
        java.util.Arrays.fill(restoreDelayNanos, FIRST_RESTORE_DELAY_NANOS);
    }
    private static int droppedThisFrame = 0;
    private static int droppedLastFrame = 0;

    /** Per view in progress, innermost last: its rectangle {x0, y0, x1, y1} in [0, 1] and whether it is dropped. */
    private static final List<double[]> RECTS = new ArrayList<>();
    private static final List<Boolean> DROPS = new ArrayList<>();

    private IPViewGuard() {
    }

    /** Once a frame, before anything is rendered; {@code viewsLastFrame} the portal views drawn in the last one. */
    public static void onFrameStart(int viewsLastFrame) {
        long now = System.nanoTime();
        Object currentLevel = net.minecraft.client.Minecraft.getInstance().level;
        if (currentLevel != lastLevel) {
            // joining a world or crossing into another dimension: chunks, LODs and pipelines load for a while
            lastLevel = currentLevel;
            settleUntilNanos = now + SETTLE_NANOS;
            levelSettleUntilNanos = settleUntilNanos;
            ringSize = 0;
        }
        if (lastFrameNanos != 0) {
            double seconds = (now - lastFrameNanos) / 1e9;
            if (seconds > HITCH_SECONDS) {
                // a pause, a loading screen or a hitch says nothing about the views
                settleUntilNanos = Math.max(settleUntilNanos, now + SETTLE_NANOS);
            }
            else {
                intervals[ringNext] = seconds;
                ends[ringNext] = now;
                ringNext = (ringNext + 1) % WINDOW_FRAMES;
                ringSize = Math.min(ringSize + 1, WINDOW_FRAMES);
            }
        }
        lastFrameNanos = now;
        frameSeconds = windowPercentile(now, 0.5);
        droppedLastFrame = droppedThisFrame;
        droppedThisFrame = 0;
        RECTS.clear();
        DROPS.clear();

        int target = IPConfig.getConfig().portalFrameRateTarget;
        if (target != lastTarget) {
            // a new target starts over with every view, and is judged at once
            lastTarget = target;
            java.util.Arrays.fill(restoreDelayNanos, FIRST_RESTORE_DELAY_NANOS);
            lastRestoreNanos = 0;
            setLevel(0, now, "target " + target);
            lastChangeNanos = 0;
        }
        if (target <= 0) {
            return;
        }
        if (viewsLastFrame > 0 || droppedLastFrame > 0) {
            lastViewsNanos = now;
        }
        else {
            if (now - lastViewsNanos > RESET_WITHOUT_VIEWS_NANOS) {
                java.util.Arrays.fill(restoreDelayNanos, FIRST_RESTORE_DELAY_NANOS);
                setLevel(0, now, "no portals in view");
            }
            return;
        }
        if (level == NEARBY_ONLY) {
            if (ClientPerformanceMonitor.getMinimumFps() > NEARBY_ONLY_LEAVE_ABOVE_FPS) {
                setLevel(MAX_LEVEL, now, "slowest second " + ClientPerformanceMonitor.getMinimumFps() + " fps");
            }
            return;
        }
        // before the settle wait: at a few fps every frame is a hitch, which would hold it off for good
        int highest = ClientPerformanceMonitor.getHighestFpsOfLast(NEARBY_ONLY_SECONDS);
        if (now >= levelSettleUntilNanos && viewsLastFrame + droppedLastFrame > NEARBY_ONLY_MIN_VIEWS
            && highest < NEARBY_ONLY_BELOW_FPS) {
            setLevel(NEARBY_ONLY, now, (viewsLastFrame + droppedLastFrame) + " views, fastest second " + highest + " fps");
            Minecraft.getInstance().gui.setOverlayMessage(
                Component.translatable("imm_ptl.portal_views_nearby_only"), false
            );
            return;
        }
        if (now < settleUntilNanos) {
            return;
        }
        boolean sinceRestore = lastRestoreNanos != 0 && lastChangeNanos == lastRestoreNanos;
        if (sinceRestore && now - lastRestoreNanos > RESTORE_GRACE_NANOS + FAILED_RESTORE_NANOS) {
            // the step given back held
            restoreDelayNanos[level] = FIRST_RESTORE_DELAY_NANOS;
        }
        double fps = 1 / frameSeconds;
        if (fps < target * DROP_BELOW) {
            boolean judged = !sinceRestore || now - lastRestoreNanos > RESTORE_GRACE_NANOS;
            if (level < MAX_LEVEL && now - lastChangeNanos > STEP_NANOS && judged) {
                if (sinceRestore && now - lastRestoreNanos < RESTORE_GRACE_NANOS + FAILED_RESTORE_NANOS) {
                    // giving this step back failed
                    restoreDelayNanos[level] = Math.min(restoreDelayNanos[level] * 2, MAX_RESTORE_DELAY_NANOS);
                }
                setLevel(level + 1, now, fpsText(fps, target));
            }
        }
        else if (fps >= target * RESTORE_FROM) {
            if (level > 0 && now - lastChangeNanos > restoreDelayNanos[level - 1]) {
                lastRestoreNanos = now;
                setLevel(level - 1, now, fpsText(fps, target));
            }
        }
    }

    /** The given percentile of the frame intervals that ended in the last {@link #WINDOW_NANOS}; 1/60 s with none. */
    private static double windowPercentile(long now, double percentile) {
        double[] recent = new double[ringSize];
        int n = 0;
        for (int i = 0; i < ringSize; i++) {
            int index = (ringNext - 1 - i + WINDOW_FRAMES) % WINDOW_FRAMES;
            if (now - ends[index] > WINDOW_NANOS) {
                break;
            }
            recent[n++] = intervals[index];
        }
        if (n == 0) {
            return 1.0 / 60;
        }
        java.util.Arrays.sort(recent, 0, n);
        return recent[Math.min(n - 1, (int) (percentile * n))];
    }

    private static String fpsText(double fps, int target) {
        return String.format(Locale.ROOT, "%.0f fps, target %d", fps, target);
    }

    private static void setLevel(int newLevel, long now, String why) {
        if (newLevel == level) {
            return;
        }
        LOGGER.info("[IPViewGuard] portal views: step {} -> {} ({})", level, newLevel, why);
        level = newLevel;
        lastChangeNanos = now;
    }

    /**
     * Around the view of {@code portal}, before its portal layer is pushed ({@code modelView} the view it is seen
     * in); always matched by {@link #pop}.
     */
    public static void push(Portal portal, Matrix4f modelView) {
        double[] parent = RECTS.isEmpty() ? new double[]{0, 0, 1, 1} : RECTS.get(RECTS.size() - 1);
        boolean parentDropped = !DROPS.isEmpty() && DROPS.get(DROPS.size() - 1);
        double[] rect = level == 0 && !parentDropped ? parent : screenRect(portal, modelView, parent);
        boolean drop = parentDropped;
        if (!drop && level > 0) {
            double area = Math.max(0, rect[2] - rect[0]) * Math.max(0, rect[3] - rect[1]);
            boolean nested = PortalRendering.getPortalLayer() >= 1;
            drop = area < (nested ? NESTED_MIN_AREA[level] : DIRECT_MIN_AREA[level]);
            if (drop) {
                droppedThisFrame++;
            }
        }
        RECTS.add(rect);
        DROPS.add(drop);
    }

    public static void pop() {
        if (!RECTS.isEmpty()) {
            RECTS.remove(RECTS.size() - 1);
            DROPS.remove(DROPS.size() - 1);
        }
    }

    /**
     * Whether only portals within 16 blocks are drawn, none seen through another ({@link #NEARBY_ONLY}, once Lag
     * Attack Proof).
     */
    public static boolean nearbyOnly() {
        return level == NEARBY_ONLY;
    }

    /** Whether the view in progress (the innermost pushed) is dropped. */
    public static boolean dropsCurrent() {
        return !DROPS.isEmpty() && DROPS.get(DROPS.size() - 1);
    }

    /**
     * The portal's rectangle on screen in [0, 1], cut to {@code parent}; {@code parent} itself when a corner lies
     * behind the camera (the portal is close: it is large).
     */
    private static double[] screenRect(Portal portal, Matrix4f modelView, double[] parent) {
        Vec3[] corners;
        if (portal.getPortalShape() instanceof RectangularPortalShape) {
            corners = FrustumCuller.getRectPortalFourVerticesCounterClockwise(portal.getThisSideState());
        }
        else {
            AABB box = portal.getThinBoundingBox();
            corners = new Vec3[8];
            for (int i = 0; i < 8; i++) {
                corners[i] = new Vec3(
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ
                );
            }
        }
        Vec3 camera = CHelper.getCurrentCameraPos();
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(modelView);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        Vector4f clip = new Vector4f();
        for (Vec3 corner : corners) {
            clip.set((float) (corner.x - camera.x), (float) (corner.y - camera.y), (float) (corner.z - camera.z), 1);
            mvp.transform(clip);
            if (clip.w < 0.05f) {
                return parent;
            }
            double x = (clip.x / clip.w + 1) / 2;
            double y = (clip.y / clip.w + 1) / 2;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[]{
            Math.max(minX, parent[0]), Math.max(minY, parent[1]),
            Math.min(maxX, parent[2]), Math.min(maxY, parent[3])
        };
    }

    /** The F3 line. */
    public static String debugText() {
        int target = IPConfig.getConfig().portalFrameRateTarget;
        if (target <= 0) {
            return "Portal Frame Rate Target: off";
        }
        return String.format(Locale.ROOT, "Portal Frame Rate Target: %d, %.0f fps, step %d, %d views dropped",
            target, 1 / frameSeconds, level, droppedLastFrame);
    }
}
