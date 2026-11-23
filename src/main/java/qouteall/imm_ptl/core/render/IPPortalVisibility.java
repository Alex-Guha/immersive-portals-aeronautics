/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import org.jetbrains.annotations.Nullable;

/**
 * Whether a portal's view is drawn, decided without waiting for the GPU.
 * <p>
 * IP tests each portal's area with an occlusion query every frame ({@code PortalRenderInfo.renderAndDecideVisibility}).
 * Upstream took last frame's result, but waited for this frame's (the render thread stops until the GPU has drawn
 * everything queued before the query) whenever the portal was hidden last frame or had been mispredicted more than five
 * times ever, up to four times a frame, and also whenever the portal had no query from last frame. With several
 * portals about, most are hidden, so it waited every frame (8.8% of the render thread with BSL in one profile).
 * <p>
 * Here: last frame's result if the GPU has it (it nearly always does), else last frame's decision; a portal tested for
 * the first time in this view is drawn, which is always right to look at (an occluded view area writes no stencil, so
 * nothing of the view shows) and costs one view for one frame. The only visible difference: a portal that was wholly
 * hidden last frame and shows now (coming out from behind a wall, a door opening) is drawn one frame late; for that
 * frame its newly visible part shows what is behind the portal, or black once its pass is set up. Upstream does the
 * same past its fourth wait in a frame.
 */
public final class IPPortalVisibility {
    /** Portals drawn every frame whatever their query says, until the time given ({@link System#nanoTime}). */
    private static final java.util.Map<java.util.UUID, Long> KEPT_DRAWN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Draws the portal {@code portalId} every frame for the next {@code millis}, without the one-frame delay above: for
     * a portal something is about to come through in view, such as a Create elevator car that fills the portal's opening
     * and hides it until its floor or roof has passed the plane; drawn a frame late, that floor or roof blinked out.
     */
    public static void keepDrawn(final java.util.UUID portalId, final long millis) {
        KEPT_DRAWN.put(portalId, System.nanoTime() + millis * 1_000_000L);
    }

    /** Whether {@link #keepDrawn} holds for this portal now. */
    public static boolean isKeptDrawn(final java.util.UUID portalId) {
        if (KEPT_DRAWN.isEmpty()) {
            return false;
        }
        final Long until = KEPT_DRAWN.get(portalId);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() > until) {
            KEPT_DRAWN.remove(portalId, until);
            return false;
        }
        return true;
    }

    /** Debug switch {@code portal_query_wait}: upstream's waiting, for comparison. */
    public static boolean waitForQueries = false;

    private IPPortalVisibility() {
    }

    /**
     * The decision for this frame; {@code lastFrameQuery} the portal's query of the last frame in this view (null if it
     * had none), {@code lastFrameDecision} what was decided then (null if nothing).
     */
    public static boolean decideWithoutWaiting(
        @Nullable GlQueryObject lastFrameQuery, @Nullable Boolean lastFrameDecision
    ) {
        if (lastFrameQuery == null) {
            return true;
        }
        int result = lastFrameQuery.tryFetchQueryResult();
        if (result < 0) {
            return lastFrameDecision == null || lastFrameDecision;
        }
        return result == 1;
    }
}
