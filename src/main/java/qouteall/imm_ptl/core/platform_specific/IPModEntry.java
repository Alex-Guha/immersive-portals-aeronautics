/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.platform_specific;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.data.loading.DatagenModLoader;
import net.neoforged.neoforge.registries.RegisterEvent;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.commands.AxisArgumentType;
import qouteall.imm_ptl.core.commands.SubCommandArgumentType;
import qouteall.imm_ptl.core.commands.TimingFunctionArgumentType;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.q_misc_util.Helper;

@Mod(IPModEntry.MODID)
public class IPModEntry {

    public static final String MODID = "immersive_portals_core";

    public IPModEntry(IEventBus modEventBus) {
        modEventBus.addListener(RegisterEvent.class, registerEvent ->
                registerEvent.register(BuiltInRegistries.ENTITY_TYPE.key(), IPModMain::registerEntityTypesForge));
        modEventBus.addListener(RegisterEvent.class, registerEvent ->
                registerEvent.register(BuiltInRegistries.BLOCK. key(), IPModMain::registerBlocksForge));
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, IPModEntryClient::initPortalRenderers);
        modEventBus.addListener(FMLDedicatedServerSetupEvent.class, event -> new IPModEntryDedicatedServer().onInitializeServer());

        if (isRealClient()) {
            new IPModEntryClient().onInitializeClient(modEventBus);
        }

        onInitialize(modEventBus);

        SubCommandArgumentType.init(modEventBus);
        TimingFunctionArgumentType.init(modEventBus);
        AxisArgumentType.init(modEventBus);
    }

    /**
     * Immersive Aeronautics: whether the client side of IP is set up. Not in data generation (runData), which runs as
     * the client dist with no {@code Minecraft} instance: the client set-up has nothing to give datagen and only
     * breaks it. Reported by a mod developer (2026-11-07): datagen hung for ever with this mod installed. Our
     * constructor began {@code RenderStateShard}'s static init (through Veil's event platform, for
     * {@code IPBalloonOverlayClip}) while Veil's and Registrate's constructors on other mod-loading threads began its
     * subclasses' ({@code OutputStateShard}, {@code RenderType}), a class-initialization deadlock; in a real client
     * those classes are initialized before mods are constructed. The peripheral mod's client init failed on the
     * missing {@code Minecraft} as well.
     */
    public static boolean isRealClient() {
        return FMLEnvironment.dist.isClient() && !DatagenModLoader.isRunningDataGen();
    }

    public void onInitialize(IEventBus eventBus) {
        IPModMain.init(eventBus);
        RequiemCompat.init();

// @Nick1st Neo moved
//        IPModMain.registerEntityTypes(
//            (id, entityType) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, entityType)
//        );
//
//        IPModMain.registerBlocks((id, obj) -> Registry.register(BuiltInRegistries.BLOCK, id, obj));
        
        if (ModList.get().isLoaded("dimthread")) {
            O_O.isDimensionalThreadingPresent = true;
            Helper.log("Dimensional Threading is present");
        }
        else {
            Helper.log("Dimensional Threading is not present");
        }
        
        if (ModList.get().isLoaded("gravity_changer_q")) {
            GravityChangerInterface.invoker = new GravityChangerInterface.OnGravityChangerPresent();
            Helper.log("Gravity API is present");
        }
        else {
            Helper.log("Gravity API is not present");
        }
        
    }
    
}
