/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollStartEvent;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * The part of {@link IPShipRagdollGuard} that touches {@code dev.leo.sableplayerragdoll} classes: cancels
 * {@link RagdollStartEvent} for players currently suppressed, and answers
 * {@link RagdollAPI#isRagdollSubLevel(SubLevel)}.
 */
final class IPShipRagdollGuardImpl {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IPShipRagdollGuardImpl() {
    }

    static boolean isRagdollSubLevel(Object obj) {
        return obj instanceof SubLevel subLevel && RagdollAPI.isRagdollSubLevel(subLevel);
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(RagdollStartEvent.class, event -> {
            try {
                ServerPlayer player = event.player();
                if (player == null) {
                    return;
                }

                long nowTick = player.server.getTickCount();
                if (IPShipRagdollGuard.isSuppressed(player.getUUID(), nowTick)) {
                    event.setCanceled(true);
                    LOGGER.debug("[IPShipRagdoll] vetoed ragdoll for {} (mid ship portal crossing)", player.getUUID());
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
