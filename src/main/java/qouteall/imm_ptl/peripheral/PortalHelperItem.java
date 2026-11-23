/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

// Immersive Aeronautics: no "deprecated, use the portal wand" chat message on placing. The Portal Helper still works,
// and packs that give it a survival recipe use it as their portal tool.
public class PortalHelperItem extends BlockItem {
    public PortalHelperItem(Block block, Properties settings) {
        super(block, settings);
    }
    
    @Override
    public void appendHoverText(
        ItemStack itemStack, Item.TooltipContext tooltipContext,
        List<Component> list, TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(itemStack, tooltipContext, list, tooltipFlag);
        
        list.add(Component.translatable("imm_ptl.portal_helper_tooltip"));
    }
}
