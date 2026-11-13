/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.ChunkGenerator;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;
import qouteall.imm_ptl.peripheral.alternate_dimension.ChaosBiomeSource;
import qouteall.imm_ptl.peripheral.alternate_dimension.ErrorTerrainGenerator;
import qouteall.imm_ptl.peripheral.alternate_dimension.FormulaGenerator;
import qouteall.imm_ptl.peripheral.alternate_dimension.NormalSkylandGenerator;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;
import qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalDrag;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

import java.util.function.BiConsumer;

public class PeripheralModMain {

    // TODO @Nick1st - Rework registry (Best would be at fabrics side)
    public static Block portalHelperBlock;

    public static final CreativeModeTab TAB = CreativeModeTab.builder()
            .icon(() -> new ItemStack(PortalWandItem.instance))
            .title(Component.translatable("imm_ptl.item_group"))
            .displayItems((enabledFeatures, entries) -> {
                PortalWandItem.addIntoCreativeTag(entries);
                
                CommandStickItem.addIntoCreativeTag(entries);
                
                entries.accept(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("immersive_portals", "portal_helper")));
            })
            .build();
    
    //@OnlyIn(Dist.CLIENT)
    public static void initClient() {
        IPOuterClientMisc.initClient();
        
        PortalWandItem.initClient();
        
        ClientPortalWandPortalDrag.init();
    }
    
    public static void init() {
        FormulaGenerator.init();
        
        IntrinsicPortalGeneration.init();
        
        DimStackManagement.init();

        // Immersive Aeronautics: back without DimLib (see AlternateDimensions)
        AlternateDimensions.init();

        PortalWandItem.init();
        
        CommandStickItem.init();
        
        PortalWandInteraction.init();
        
        CommandStickItem.registerCommandStickTypes();

    }
    
    public static void registerItems(BiConsumer<ResourceLocation, Item> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
                new PortalHelperItem(PeripheralModMain.portalHelperBlock, new Item.Properties())
        );

        CommandStickItem commandStickItem = new CommandStickItem(new Item.Properties());
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:command_stick"),
                commandStickItem
        );
        CommandStickItem.instance = commandStickItem;

        PortalWandItem portalWandItem = new PortalWandItem(new Item.Properties());
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:portal_wand"),
                portalWandItem
        );
        PortalWandItem.instance = portalWandItem;
    }
    
    public static void registerBlocks(BiConsumer<ResourceLocation, Block> regFunc) {
        Block block = new Block(BlockBehaviour.Properties.of().noOcclusion().isRedstoneConductor((a, b, c) -> false));
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
                block
        );
        portalHelperBlock = block;
    }

    public static void registerChunkGenerators(
        BiConsumer<ResourceLocation, MapCodec<? extends ChunkGenerator>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:error_terrain_generator"),
            ErrorTerrainGenerator.MAP_CODEC
        );
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:normal_skyland_generator"),
            NormalSkylandGenerator.MAP_CODEC
        );
    }

    public static void registerBiomeSources(
        BiConsumer<ResourceLocation, MapCodec<? extends BiomeSource>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals:chaos_biome_source"),
            ChaosBiomeSource.MAP_CODEC
        );
    }

    public static void registerCreativeTabs(
        BiConsumer<ResourceLocation, CreativeModeTab> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "general"),
            TAB
        );
    }
}
