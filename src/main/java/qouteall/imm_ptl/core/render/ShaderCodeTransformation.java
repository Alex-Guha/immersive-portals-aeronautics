/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.logging.LogUtils;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.Helper;

import java.util.List;
import java.util.Set;

public class ShaderCodeTransformation {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static enum ShaderType {
        vs, fs
    }
    
    private static boolean matches(ShaderType me, Program.Type type) {
        if (type == Program.Type.FRAGMENT) {
            return me == ShaderType.fs;
        }
        else if (type == Program.Type.VERTEX) {
            return me == ShaderType.vs;
        }
        return false;
    }
    
    // snakeyaml does not allow passing generic type
    // so use another wrapper type to make list generic type work
    public static class ConfigsObj {
        public List<Config> configs;
    }
    
    public static class TransformationEntry {
        public String comment;
        public String pattern;
        public String replacement;
    }
    
    public static class Config {
        public String comment;
        public ShaderType type;
        public Set<String> affectedShaders;
        public List<TransformationEntry> transformations;
        public boolean debugOutput;
    }
    
    private static List<Config> configs;
    
    public static void init() {
        if (IPGlobal.enableClippingMechanism) {
            Yaml yaml = new Yaml();
            
            String yamlStr = McHelper.readTextResource(McHelper.newResourceLocation(
                "immersive_portals:shaders/shader_transformation.yaml"
            ));
            ConfigsObj configsObj = yaml.loadAs(yamlStr, ConfigsObj.class);
            
            configs = configsObj.configs;
            
            LOGGER.info("Loaded Shader Code Transformation");
        }
        else {
            LOGGER.info("Shader Transformation Disabled");
        }
    }
    
    public static String transform(Program.Type type, String shaderId, String inputCode) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {}", shaderId);
            return inputCode;
        }
        
        Config selected = getConfig(type, shaderId);
        
        if (selected == null) {
            return inputCode;
        }
        
        String result = inputCode;
        
        for (TransformationEntry entry : selected.transformations) {
            String replacement = String.join("\n", entry.replacement);
            result = result.replaceAll(entry.pattern, replacement);
        }
        
        if (selected.debugOutput) {
            LOGGER.info("Shader Transformed {}\n{}", shaderId, result);
        }
        
        return result;
    }
    
    @Nullable
    private static Config getConfig(Program.Type type, String shaderId) {
        return configs.stream().filter(
            config -> matches(config.type, type) &&
                config.affectedShaders.contains(shaderId)
        ).findFirst().orElse(null);
    }
    
    /**
     * Immersive Aeronautics: the vanilla terrain shaders (the first entry of shader_transformation.yaml), which also draw
     * Sable ships and get the straddle clip plane {@code ipa_ShipClipEquation} (gl_ClipDistance[1]).
     */
    public static final Set<String> TERRAIN_SHADERS = Set.of(
        "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent"
    );
    
    public static boolean shouldAddUniform(String shaderName) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {} in shouldAddUniform", shaderName);
            return false;
        }
        
        return configs.stream().anyMatch(config -> config.affectedShaders.contains(shaderName));
    }
}
