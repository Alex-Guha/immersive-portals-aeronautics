/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for {@code MixinDhConfigEntry_IgnoreGenWithC2ME}: detects C2ME (via {@link ModList} or its
 * main class) and reflectively resolves Distant Horizons' {@code enableDistantGeneration} /
 * {@code enableServerGeneration} config entries. {@link #shouldForceDisable(Object)} is true only for
 * those entry instances while C2ME is present, which forces DH distant world generation off.
 */
public class IPDhGenGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * DH config entries to gate, as {owner class, static field}. Resolved reflectively because they
     * are DH internals. Note: DH 3.3.x no longer has either field (the entries were removed from
     * {@code Config.Common.WorldGenerator} / {@code Config.Server}), so on DH 3.3.x the lookup
     * resolves nothing and this guard is inert.
     */
    private static final String[][] GEN_ENTRIES = new String[][]{
        {"com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator", "enableDistantGeneration"},
        {"com.seibel.distanthorizons.core.config.Config$Server", "enableServerGeneration"}
    };
    private static Boolean c2mePresent = null;
    private static List<Object> gatedEntries = null;
    private static boolean logged = false;
    private static int resolveAttempts = 0;

    private static boolean isC2mePresent() {
        if (c2mePresent == null) {
            try {
                c2mePresent = ModList.get().isLoaded("c2me");
            } catch (Throwable e) {
                // ModList not available yet: fall back to probing C2ME's main class
                try {
                    Class.forName("com.ishland.c2me.C2MEMod");
                    c2mePresent = Boolean.TRUE;
                } catch (Throwable e2) {
                    return false;
                }
            }
        }

        return c2mePresent;
    }

    private static List<Object> gatedEntries() {
        if (gatedEntries != null) {
            return gatedEntries;
        }
        if (resolveAttempts > 200) {
            return null;
        }

        resolveAttempts++;
        ArrayList<Object> resolved = new ArrayList<>();

        for (String[] entry : GEN_ENTRIES) {
            try {
                Class<?> owner = Class.forName(entry[0]);
                Field field = owner.getDeclaredField(entry[1]);
                field.setAccessible(true);
                Object configEntry = field.get(null);
                if (configEntry != null) {
                    resolved.add(configEntry);
                }
            } catch (Throwable ignored) {
            }
        }

        if (resolved.isEmpty()) {
            return null;
        }

        gatedEntries = resolved;
        return gatedEntries;
    }

    public static boolean shouldForceDisable(Object configEntry) {
        try {
            if (configEntry == null) {
                return false;
            }
            if (!isC2mePresent()) {
                return false;
            }

            List<Object> gated = gatedEntries();
            if (gated == null) {
                return false;
            }

            for (int i = 0; i < gated.size(); i++) {
                if (gated.get(i) == configEntry) {
                    if (!logged) {
                        logged = true;
                        LOGGER.debug(
                            "[IPDhGenGuard] c2me detected - forcing Distant Horizons distant world generation OFF (DH still renders existing LODs and builds them from chunks the game loads). Gated {} DH config entr(ies).",
                            gated.size()
                        );
                    }

                    return true;
                }
            }

            return false;
        } catch (Throwable e) {
            return false;
        }
    }
}
