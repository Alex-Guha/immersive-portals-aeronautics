/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portal view distance ({@link IPPortalViewDistance}) with Complementary (Unbound, Reimagined, Euphoria Patches): its
 * border fog runs from the camera to its fog distance along a fixed curve (linear in the Nether), so a fog distance
 * close past a distant portal fogged the portal itself. In a pass with a cut, {@code DoBorderFog}'s distance is
 * remapped so the pack's own curve runs over a band just before the cut: 0 before {@code ipa_portalFogStart}, 1.25 of
 * the pack's fog distance (opaque in every branch of it) at {@code ipa_portalFogEnd}. With both at 0 (no cut, other
 * passes, the player's view) the function is unchanged. Packs without that function are left alone.
 */
@Mixin(value = TransformPatcher.class, remap = false)
public class MixinIrisTransformPatcher_PortalViewFog {
    @Unique
    private static final Pattern ip_BORDER_FOG = Pattern.compile(
        "void\\s+DoBorderFog\\s*\\([^)]*\\bfloat\\s+lPos\\b[^)]*\\)\\s*\\{"
    );

    @Inject(method = "transformInternal", at = @At("RETURN"))
    private static void ip_patchBorderFog(
        String name, Map<PatchShaderType, String> inputs, Parameters parameters,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        Map<PatchShaderType, String> map = cir.getReturnValue();
        String code = map.get(PatchShaderType.FRAGMENT);
        if (code == null || code.contains(IPPortalViewDistance.PACK_FOG_END_UNIFORM)) {
            return;
        }
        Matcher m = ip_BORDER_FOG.matcher(code);
        if (!m.find()) {
            return;
        }
        String start = IPPortalViewDistance.PACK_FOG_START_UNIFORM;
        String end = IPPortalViewDistance.PACK_FOG_END_UNIFORM;
        String patched = code.substring(0, m.start())
            + "uniform float " + start + ";\nuniform float " + end + ";\n"
            + m.group()
            + "\n    if (" + end + " > 0.0) lPos = 1.25 * renderDistance * max(0.0, lPos - " + start + ") / max("
            + end + " - " + start + ", 1.0);\n"
            + code.substring(m.end());
        map.put(PatchShaderType.FRAGMENT, patched);
    }

    /** Runs for cached transforms too: the pack loaded since the last reload has the patched fog. */
    @Inject(method = "transform", at = @At("RETURN"))
    private static void ip_notePatchedBorderFog(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        Parameters parameters, CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        String code = cir.getReturnValue().get(PatchShaderType.FRAGMENT);
        if (code != null && code.contains(IPPortalViewDistance.PACK_FOG_END_UNIFORM)) {
            IPPortalViewDistance.packFogPatched = true;
        }
    }
}
