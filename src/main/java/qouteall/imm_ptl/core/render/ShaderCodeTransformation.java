/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.Helper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    
    // the wrapper type gives the list its generic type
    public static class ConfigsObj {
        public List<Config> configs;
    }
    
    public static class TransformationEntry {
        public String comment;
        public String pattern;
        public String replacement;
        // Immersive Aeronautics: a name the replacement uses; a shader without it skips this entry
        public String requires;
    }
    
    public static class Config {
        public String comment;
        public ShaderType type;
        public Set<String> affectedShaders;
        public List<TransformationEntry> transformations;
        public boolean debugOutput;
    }
    
    private static List<Config> configs;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    
    public static void init() {
        if (IPGlobal.enableClippingMechanism) {
            // Immersive Aeronautics: JSON read with Gson (was YAML read with Cloth Config's shaded SnakeYAML)
            String jsonStr = McHelper.readTextResource(McHelper.newResourceLocation(
                "immersive_portals:shaders/shader_transformation.json"
            ));
            ConfigsObj configsObj = IPGlobal.gson.fromJson(jsonStr, ConfigsObj.class);
            
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
            // Immersive Aeronautics: nothing in the game shows a shader that lost its clip plane, so the log does
            if (entry.requires != null && !result.contains(entry.requires)) {
                warnOnce(shaderId, "it has no " + entry.requires);
                continue;
            }
            String replacement = String.join("\n", entry.replacement);
            String replaced = result.replaceAll(entry.pattern, replacement);
            if (replaced.equals(result)) {
                warnOnce(shaderId, "the pattern " + entry.pattern + " found nothing");
            }
            result = replaced;
        }
        
        if (selected.debugOutput) {
            LOGGER.info("Shader Transformed {}\n{}", shaderId, result);
        }
        
        return result;
    }
    
    private static void warnOnce(String shaderId, String why) {
        if (WARNED.add(shaderId + why)) {
            LOGGER.warn("Shader {} is not fully clipped in portal views: {}", shaderId, why);
        }
    }
    
    @Nullable
    private static Config getConfig(Program.Type type, String shaderId) {
        return configs.stream().filter(
            config -> matches(config.type, type) &&
                config.affectedShaders.contains(shaderId)
        ).findFirst().orElse(null);
    }
    
    /**
     * Immersive Aeronautics: the vanilla terrain shaders (the first entry of shader_transformation.json), which also draw
     * Sable ships and get the straddle clip plane {@code ipa_ShipClipEquation} (gl_ClipDistance[1]).
     */
    public static final Set<String> TERRAIN_SHADERS = Set.of(
        "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent"
    );
    
    /**
     * Immersive Aeronautics: an Iris shader pack's vanilla-path programs standing in for the vanilla terrain shaders
     * (Iris's {@code ShaderKey} names; shader_transformation.json patches them as {@code iris_<name>}). They draw Sable
     * ships while a pack is active and get both planes, IP's and the straddle cut.
     */
    public static final Set<String> IRIS_TERRAIN_SHADERS = Set.of(
        "terrain_solid", "terrain_cutout", "terrain_translucent", "moving_block"
    );

    /**
     * Immersive Aeronautics: an Iris shader pack's vanilla-path entity, block entity, text and particle programs
     * ({@code ShaderKey} names), patched with IP's clip plane like the vanilla entity shaders.
     */
    public static final Set<String> IRIS_ENTITY_SHADERS = Set.of(
        "entities_alpha", "entities_solid", "entities_solid_diffuse", "entities_solid_bright", "entities_cutout",
        "entities_cutout_diffuse", "entities_translucent", "entities_eyes", "entities_eyes_trans", "block_entity",
        "block_entity_bright", "block_entity_diffuse", "be_translucent", "text_be", "text_intensity_be", "text",
        "text_intensity", "text_bg", "leash", "beacon", "glint", "crumbling", "particles", "particles_trans"
    );

    private static final String IRIS_EXTENDED_SHADER = "net.irisshaders.iris.pipeline.programs.ExtendedShader";

    public static boolean isIrisTerrainShader(String shaderName, Object shader) {
        return IRIS_TERRAIN_SHADERS.contains(shaderName) && shader.getClass().getName().equals(IRIS_EXTENDED_SHADER);
    }

    public static boolean isIrisEntityShader(String shaderName, Object shader) {
        return IRIS_ENTITY_SHADERS.contains(shaderName) && shader.getClass().getName().equals(IRIS_EXTENDED_SHADER);
    }

    public static boolean shouldAddUniform(String shaderName) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {} in shouldAddUniform", shaderName);
            return false;
        }
        
        return configs.stream().anyMatch(config -> config.affectedShaders.contains(shaderName));
    }
}
