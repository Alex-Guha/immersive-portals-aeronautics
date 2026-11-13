/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.Util;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPMixinReport;
import qouteall.imm_ptl.core.compat.sable_integration.IPPymCompat;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Our physics library under Sable's own Rapier classes (docs/lessons/physics-backend.md, D3). Our {@code ipa_rapier}
 * library implements Sable's {@code Rapier3D} natives under Sable's JNI names, plus {@link RapierExt}'s. When
 * {@code Rapier3D} is initialised, {@link #loadInPlaceOfSable} decides once per JVM whether ours is loaded instead of
 * Sable's {@code sable_rapier}; Sable's own pipeline classes then drive it, so other mods' hooks on them act on our
 * physics too. Otherwise Sable loads its own library and nothing of ours touches its physics.
 * <p>
 * Every mixin on Sable's Rapier classes ({@code compat.mixin_sable.MixinSableRapier*}) does nothing unless
 * {@link #isOwnBackendActive()}; none is applied for a Sable version not in {@link IPSableBackendVersion#SUPPORTED}.
 */
public final class IPPhysicsBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code -Dip_aeronautics.stockPhysics=true} lets Sable load its own physics library. */
    public static final String KILL_SWITCH_PROPERTY = "ip_aeronautics.stockPhysics";

    private static final String LIB_NAME = "ipa_rapier";

    private static volatile boolean decided;
    private static volatile boolean ownBackendActive;
    /** Why ships cannot straddle, when it was not asked for. */
    @Nullable
    private static volatile String fallbackReason;

    private IPPhysicsBackend() {
    }

    /**
     * @return whether our library runs every level's physics (decided once per JVM, when Sable's {@code Rapier3D} is
     * initialised: the first physics pipeline started)
     */
    public static boolean isOwnBackendActive() {
        return ownBackendActive;
    }

    /** @return why ships cannot straddle portals although nobody switched it off, or null */
    @Nullable
    public static String getFallbackReason() {
        final String reason = fallbackReason;
        if (reason == null && !ownBackendActive && !IPSableBackendVersion.isSupported()) {
            // no mixin of ours is on Sable's Rapier classes, so the loader never ran
            return IPSableBackendVersion.unsupportedReason();
        }
        return reason;
    }

    /**
     * Makes sure the decision is taken: initialises Sable's {@code Rapier3D}, which loads a physics library. Called
     * where Sable starts a pipeline.
     */
    public static void decide() {
        if (decided) {
            return;
        }
        try {
            Class.forName(Rapier3D.class.getName(), true, Rapier3D.class.getClassLoader());
        } catch (final Throwable t) {
            // Sable's own load failed: it reports that itself when the pipeline starts
            LOGGER.error("[IPPhysics] Sable's Rapier3D could not be initialised", t);
        }
    }

    /**
     * Head of Sable's {@code Rapier3D.loadLibrary}, from {@code Rapier3D}'s static initialiser.
     *
     * @return true when our library is loaded and Sable's must not be
     */
    public static boolean loadInPlaceOfSable() {
        if (decided) {
            return ownBackendActive;
        }
        decided = true;

        if (Boolean.getBoolean(KILL_SWITCH_PROPERTY)) {
            LOGGER.info("[IPPhysics] -D{}=true: using Sable's stock physics", KILL_SWITCH_PROPERTY);
            return false;
        }

        if (!IPSableBackendVersion.isSupported()) {
            return fallBack(IPSableBackendVersion.unsupportedReason());
        }

        // Pym scales ships through Sable's classes, so it runs on our library; straddling a scaled ship needs
        // its scale query
        final String pymProblem = IPPymCompat.queryProblem();
        if (pymProblem != null) {
            return fallBack("Create: Pocket Sized (Pym) is installed, but its ships' scale cannot be read (" + pymProblem + ")");
        }

        // JNI binds a native method in the libraries its class's loader loaded: Sable's Rapier3D and our RapierExt
        // must share one, as NeoForge's mods normally do.
        if (Rapier3D.class.getClassLoader() != RapierExt.class.getClassLoader()) {
            LOGGER.warn("[IPPhysics] Sable's classes ({}) and ours ({}) have different class loaders",
                    Rapier3D.class.getClassLoader(), RapierExt.class.getClassLoader());
            return fallBack("the physics library cannot be shared with Sable here");
        }

        final String otherLoaders = otherModsLoadingNatives();
        if (otherLoaders != null) {
            return fallBack("another mod replaces Sable's physics library (" + otherLoaders + ")");
        }

        final String nativeName = nativeName();
        if (nativeName == null) {
            return fallBack("there is no physics library for " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        }

        final Path file;
        try {
            file = extract(nativeName);
            System.load(file.toAbsolutePath().toString());
        } catch (final Throwable t) {
            LOGGER.error("[IPPhysics] could not load {}", nativeName, t);
            return fallBack("the physics library could not be loaded");
        }

        ownBackendActive = true;
        LOGGER.info("[IPPhysics] loaded {} from {} in place of Sable's Rapier library: ships can straddle portals",
                nativeName, file.getParent().toAbsolutePath());
        return true;
    }

    private static boolean fallBack(final String reason) {
        fallbackReason = reason;
        LOGGER.warn("[IPPhysics] using Sable's stock physics, ships cannot straddle portals: {}", reason);
        return false;
    }

    /**
     * @return other mods' mixins on {@code Rapier3D.loadLibrary} (IPSable loads its own library there), or null
     */
    @Nullable
    private static String otherModsLoadingNatives() {
        try {
            final Map<String, Set<String>> mixins = IPMixinReport.otherModsMixins(Rapier3D.class, Set.of("loadLibrary"));
            final String hooking = mixins.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
            return hooking.isEmpty() ? null : hooking;
        } catch (final Throwable t) {
            LOGGER.warn("[IPPhysics] could not list other mods' hooks into Sable's Rapier3D", t);
            return null;
        }
    }

    /** @return the library's file name for this platform, or null when we build none for it */
    @Nullable
    private static String nativeName() {
        final String osArch = System.getProperty("os.arch");
        final String arch;
        if (osArch.equals("arm") || osArch.startsWith("aarch64")) {
            arch = "aarch64";
        } else if (osArch.equals("amd64") || osArch.equals("x86_64")) {
            arch = "x86_64";
        } else {
            return null;
        }

        return switch (Util.getPlatform()) {
            case WINDOWS -> LIB_NAME + "_" + arch + "_windows.dll";
            case OSX -> LIB_NAME + "_" + arch + "_macos.dylib";
            case LINUX -> LIB_NAME + "_" + arch + "_linux.so";
            default -> null;
        };
    }

    /**
     * Copies the library out of our jar to {@code <game dir>/.ip_aeronautics/natives/}.
     */
    private static Path extract(final String nativeName) throws Exception {
        final byte[] bytes;
        try (final InputStream is = IPPhysicsBackend.class.getResourceAsStream("/natives/" + LIB_NAME + "/" + nativeName)) {
            if (is == null) {
                throw new FileNotFoundException(nativeName);
            }
            bytes = is.readAllBytes();
        }

        final Path dir = FMLPaths.GAMEDIR.get().resolve(".ip_aeronautics").resolve("natives").normalize();
        Files.createDirectories(dir);
        final Path file = dir.resolve(nativeName);
        // A library another game instance has loaded cannot be replaced on Windows; reuse it when it is identical.
        if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
            Files.write(file, bytes);
        }
        return file;
    }
}
