/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.platform_specific;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entry point of our own mod id: gives its Mods list entry the Config button. The mod's setup runs from the
 * Immersive Portals entry points ({@link IPModEntry} and the others).
 */
@Mod(value = "ip_aeronautics", dist = Dist.CLIENT)
public class IPAeronauticsClientEntry {
    public IPAeronauticsClientEntry(ModContainer container) {
        container.registerExtensionPoint(
            IConfigScreenFactory.class, (modContainer, parent) -> new IPConfigScreen(parent)
        );
    }
}
