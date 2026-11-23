/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sodium_compatibility;

public interface IESodiumRenderSectionManager {
    void ip_swapContext(SodiumRenderingContext context);
    
    // Immersive Aeronautics: the outer world's lists, put in for a draw inside a portal view (IPNearTranslucents)
    void ip_setRenderLists(net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists renderLists);
}
