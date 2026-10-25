/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.core.model.ColourDepthTextureData;
import me.cortex.voxy.client.core.model.MipGen;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code MipGen.putTextures} works in a static scratch array and queue, called from each Voxy render system's own
 * model-baking thread. Stock Voxy has one render system at a time; with IP every client dimension has one, and two
 * baking threads at once corrupted the queue ({@code ArrayIndexOutOfBoundsException: arraycopy: length -77 is
 * negative} in {@code ByteArrayFIFOQueue}, rethrown on the render thread, a crash; a race that does not throw mixes
 * up the textures of two models). One call at a time; Roxy reaches it by reflection
 * ({@code RoxyTextureTintBridge.putTextures}), which runs the wrapped method too.
 */
@Mixin(value = MipGen.class, remap = false)
public class MixinVoxyMipGen_OneAtATime {
    @Unique
    private static final Object IP_LOCK = new Object();

    @WrapMethod(method = "putTextures", require = 0)
    private static void ip_oneAtATime(
        boolean darkened, ColourDepthTextureData[] textures, MemoryBuffer into, Operation<Void> original
    ) {
        synchronized (IP_LOCK) {
            original.call(darkened, textures, into);
        }
    }
}
