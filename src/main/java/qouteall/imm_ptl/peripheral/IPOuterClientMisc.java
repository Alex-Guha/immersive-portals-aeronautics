/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientPortalSpawnEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackInfo;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

//@OnlyIn(Dist.CLIENT)
public class IPOuterClientMisc {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static class OuterConfig {
        public boolean wikiInformed = false;
        public boolean portalHelperInformed = false;
        public boolean lagInformed = false;
        
        // the dimension stack preset has been moved into IPConfig
        // this is still here for deserialization for upgrade
        @Nullable
        public DimStackInfo dimensionStackDefault = null;
        
        public OuterConfig() {}
    }
    
    private static OuterConfig readFromFile() {
        File storageFile = getStorageFile();
        
        if (storageFile.exists()) {
            
            OuterConfig result = null;
            try (FileReader fileReader = new FileReader(storageFile)) {
                result = IPGlobal.gson.fromJson(fileReader, OuterConfig.class);
            } catch (Throwable e) {
                e.printStackTrace();
                return new OuterConfig();
            }
            
            if (result == null) {
                return new OuterConfig();
            }
            
            return result;
        }
        
        return new OuterConfig();
    }
    
    private static File getStorageFile() {
        return new File(Minecraft.getInstance().gameDirectory, "imm_ptl_state.json");
    }
    
    private static void writeToFile(OuterConfig outerConfig) {
        try (FileWriter fileWriter = new FileWriter(getStorageFile())) {
            
            IPGlobal.gson.toJson(outerConfig, fileWriter);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static OuterConfig outerConfig = new OuterConfig();
    
    public static void initClient() {
        outerConfig = readFromFile();
        
        // upgrade dimension stack preset
        DimStackInfo dimStackPreset = outerConfig.dimensionStackDefault;
        if (dimStackPreset != null) {
            try {
                IPConfig config = IPConfig.getConfig();
                config.dimStackPreset = ((JsonObject) IPGlobal.gson.toJsonTree(dimStackPreset));
                config.saveConfigFile();
                outerConfig.dimensionStackDefault = null;
                writeToFile(outerConfig);
                LOGGER.info("Successfully upgraded dimension stack preset");
            }
            catch (Exception e) {
                LOGGER.info("Failed to upgrade dimension stack preset", e);
            }
        }

        // Immersive Aeronautics: the one-time "check the wiki" chat message was removed.
    }
    }
