/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineProvider;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipelineProvider;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;

/**
 * Sable physics provider for ship straddling (docs/lessons/physics-backend.md, D3). Sable picks the {@code ServiceLoader}
 * provider with the highest {@link PhysicsPipelineProvider.LoadPriority}; Sable's Rapier provider uses the default
 * 1000. Every level gets our own copy of Sable's Rapier backend ({@code ipaeronautics.rapier}, native library
 * {@code ipa_rapier}) when its natives loaded, the Sable version is one it was copied from, no mod that needs Sable's
 * own backend is loaded, and the kill switch is off. Otherwise every level gets Sable's stock Rapier pipeline. The choice is made once per JVM and is the same for
 * every level, since the backend's scene lookup only works for levels using it.
 */
@PhysicsPipelineProvider.LoadPriority(IPPhysicsPipelineProvider.PRIORITY)
public final class IPPhysicsPipelineProvider implements PhysicsPipelineProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int PRIORITY = 1100;

    /**
     * {@code -Dip_aeronautics.stockPhysics=true} forces Sable's own pipeline.
     */
    public static final String KILL_SWITCH_PROPERTY = "ip_aeronautics.stockPhysics";

    /**
     * Sable versions whose Rapier backend {@code ipaeronautics.rapier} was copied from.
     */
    private static final Set<String> SUPPORTED_SABLE_VERSIONS = Set.of("2.0.6");

    /**
     * Mods that call and mix into Sable's own Rapier classes ({@code dev.ryanhcode.sable.physics.impl.rapier}) and do
     * nothing on ours, so their physics changes would be lost. Pym, bundled with Create: Pocket Sized, scales ships'
     * colliders that way (docs/lessons/mod-compat.md).
     */
    private static final Set<String> STOCK_BACKEND_MODS = Set.of("pym");

    private static volatile boolean ownBackendActive;
    /** Why ships cannot straddle, when it was not asked for. */
    @Nullable
    private static volatile String fallbackReason;

    private final PhysicsPipelineProvider stock = new RapierPhysicsPipelineProvider();
    @Nullable
    private PhysicsPipelineProvider own;
    private boolean decided;

    /**
     * @return whether every level's pipeline is ours (decided once per JVM, at the first pipeline created)
     */
    public static boolean isOwnBackendActive() {
        return ownBackendActive;
    }

    /** @return why ships cannot straddle portals although nobody switched it off, or null */
    @Nullable
    public static String getFallbackReason() {
        return fallbackReason;
    }

    @Override
    public @NotNull PhysicsPipeline createPipeline(@NotNull final ServerLevel level) {
        if (!this.decided) {
            this.decided = true;
            this.own = decide();
            ownBackendActive = this.own != null;
        }

        if (this.own != null) {
            LOGGER.info("[IPPhysics] {}: using the ipa_rapier pipeline", level.dimension().location());
            return this.own.createPipeline(level);
        }

        LOGGER.info("[IPPhysics] {}: using Sable's stock Rapier pipeline", level.dimension().location());
        return this.stock.createPipeline(level);
    }

    /**
     * @return our backend's provider, or null to use Sable's
     */
    @Nullable
    private static PhysicsPipelineProvider decide() {
        if (Boolean.getBoolean(KILL_SWITCH_PROPERTY)) {
            LOGGER.info("[IPPhysics] -D{}=true: using Sable's stock physics", KILL_SWITCH_PROPERTY);
            return null;
        }

        final String sableVersion = ModList.get().getModContainerById("sable")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("absent");
        if (!SUPPORTED_SABLE_VERSIONS.contains(sableVersion)) {
            LOGGER.warn("[IPPhysics] Sable {} is not one of {}: using Sable's stock physics, ships cannot straddle portals",
                    sableVersion, SUPPORTED_SABLE_VERSIONS);
            fallbackReason = "Sable " + sableVersion + " is not a supported version (" + String.join(", ", SUPPORTED_SABLE_VERSIONS) + ")";
            return null;
        }

        for (final String modId : STOCK_BACKEND_MODS) {
            if (ModList.get().isLoaded(modId)) {
                LOGGER.warn("[IPPhysics] {} works only with Sable's own Rapier backend: using Sable's stock physics, ships cannot straddle portals",
                        modId);
                fallbackReason = "the mod " + modId + " needs Sable's own physics";
                return null;
            }
        }

        try {
            if (!ipaeronautics.rapier.Rapier3D.isLoaded()) {
                LOGGER.warn("[IPPhysics] ipa_rapier natives unavailable ({}): using Sable's stock physics, ships cannot straddle portals",
                        ipaeronautics.rapier.Rapier3D.getLoadFailure().toString());
                fallbackReason = "the physics library could not be loaded";
                return null;
            }
        } catch (final Throwable t) {
            LOGGER.error("[IPPhysics] could not initialise ipa_rapier: using Sable's stock physics", t);
            fallbackReason = "the physics library could not be started";
            return null;
        }

        return new ipaeronautics.rapier.RapierPhysicsPipelineProvider();
    }
}
