/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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
