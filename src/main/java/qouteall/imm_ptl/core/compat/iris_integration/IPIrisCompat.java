/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Safe entry point for querying Iris: {@link #isShaderPackInUse()} returns false unless the
 * {@code iris} mod is loaded (checked once via {@link ModList}), and only then touches the
 * Iris API through {@link IPIrisCompatImpl} so no Iris classes are loaded without Iris.
 */
public final class IPIrisCompat {
    private static boolean checked;
    private static boolean irisPresent;

    private IPIrisCompat() {
    }

    private static boolean isIrisPresent() {
        if (!checked) {
            checked = true;

            try {
                irisPresent = ModList.get().isLoaded("iris");
            } catch (Throwable t) {
                irisPresent = false;
            }
        }

        return irisPresent;
    }

    public static boolean isShaderPackInUse() {
        if (!isIrisPresent()) {
            return false;
        }
        try {
            return IPIrisCompatImpl.isShaderPackInUse();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Immersive Aeronautics: {@code source} as Iris's block entity dispatcher hands it to block entity renderers
     * ({@code MixinBlockEntityRenderDispatcher}), for geometry drawn alongside block entities but not by one: with a
     * shader pack its render types switch to the pack's block entity programs when drawn, and its vertices carry
     * {@code state}'s block id. End with {@link #endBlockEntity()}. Without a pack or Iris, {@code source} itself.
     */
    public static MultiBufferSource beginBlockEntity(MultiBufferSource source, BlockState state) {
        if (!isIrisPresent()) {
            return source;
        }
        try {
            return IPIrisCompatImpl.beginBlockEntity(source, state);
        } catch (Throwable t) {
            return source;
        }
    }

    public static void endBlockEntity() {
        if (!isIrisPresent()) {
            return;
        }
        try {
            IPIrisCompatImpl.endBlockEntity();
        } catch (Throwable t) {
            // as beginBlockEntity
        }
    }
}
