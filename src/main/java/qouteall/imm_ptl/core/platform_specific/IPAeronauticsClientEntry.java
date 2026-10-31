/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.platform_specific;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.Set;

/**
 * Client entry point of our own mod id: gives its Mods list entry the Config button. The mod's setup runs from the
 * Immersive Portals entry points ({@link IPModEntry} and the others).
 */
@Mod(value = "ip_aeronautics", dist = Dist.CLIENT)
public class IPAeronauticsClientEntry {
    /**
     * The Immersive Portals mod ids our jar also declares. They stay declared, since other mods detect and depend on
     * them, but mod lists show only {@code ip_aeronautics}: NeoForge's ({@code MixinModListScreen}) and Mod Menu's
     * ({@code MixinModMenu_OneEntry}).
     */
    public static final Set<String> IP_ALIAS_IDS =
        Set.of("immersive_portals_core", "immersive_portals", "imm_ptl", "q_misc_util");

    public IPAeronauticsClientEntry(ModContainer container) {
        container.registerExtensionPoint(
            IConfigScreenFactory.class, (modContainer, parent) -> new IPConfigScreen(parent)
        );
    }
}
