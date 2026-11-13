/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.Set;

/**
 * Which Sable versions our physics library can stand in for (docs/lessons/physics-backend.md, D3). The library
 * implements Sable's {@code Rapier3D} natives under Sable's own JNI names, and JNI matches names, not signatures: a
 * Sable whose natives differ would crash the JVM. So the list is exact, and our mixins on Sable's Rapier classes are
 * not even applied for another version ({@code IPOptionalModMixinPlugin}). Usable while mixins are configured: reads
 * FML's loading mod list, nothing of the game.
 */
public final class IPSableBackendVersion {
    /** Sable versions whose {@code Rapier3D} natives ours match, member for member. */
    public static final Set<String> SUPPORTED = Set.of("2.0.6");

    private IPSableBackendVersion() {
    }

    /** @return Sable's version, or "absent" */
    public static String sableVersion() {
        final LoadingModList mods = LoadingModList.get();
        final ModFileInfo file = mods == null ? null : mods.getModFileById("sable");
        if (file == null) {
            return "absent";
        }
        for (final IModInfo mod : file.getMods()) {
            if (mod.getModId().equals("sable")) {
                return mod.getVersion().toString();
            }
        }
        return "absent";
    }

    public static boolean isSupported() {
        return SUPPORTED.contains(sableVersion());
    }

    /** @return why our physics cannot run with this Sable, for players and the log */
    public static String unsupportedReason() {
        return "Sable " + sableVersion() + " is not a supported version (" + String.join(", ", SUPPORTED) + ")";
    }
}
