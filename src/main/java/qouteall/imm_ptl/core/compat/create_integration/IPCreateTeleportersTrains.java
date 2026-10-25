/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.neoforged.fml.ModList;

/**
 * Create Teleporters' train code gives each side of a portal within one dimension a dimension key of its own, switched
 * on for a portal track carrying its two tags ({@code CTPTrainBase}, {@code CTPTrainTargetBase}: the portal bases at
 * this end and the other). 2.5.3-2.5.7 tagged pairs of ours at its portals that way so its code took trains across;
 * since 2.5.8 our own code does it at any portal ({@link IPCreatePortalEdges}, {@link IPCreateTrainSides}) and pairs are
 * no longer tagged. Pairs tagged before are left to Create Teleporters while it is installed (its nodes carry its own
 * portal flag in Create's saved graph); the tags go when the pair is unlinked.
 */
public final class IPCreateTeleportersTrains {
    private static final String BASE = "CTPTrainBase";
    private static final String TARGET_BASE = "CTPTrainTargetBase";

    private IPCreateTeleportersTrains() {
    }

    /** Whether a track carries Create Teleporters' train tags (set by 2.5.3-2.5.7) and Create Teleporters is installed. */
    public static boolean isLeftToIt(final TrackBlockEntity track) {
        return (track.getPersistentData().contains(BASE) || track.getPersistentData().contains(TARGET_BASE))
            && ModList.get().isLoaded("createteleporters");
    }

    /** Removes Create Teleporters' tags from a track that is no longer a portal track. */
    public static void untag(final TrackBlockEntity track) {
        track.getPersistentData().remove(BASE);
        track.getPersistentData().remove(TARGET_BASE);
    }
}
