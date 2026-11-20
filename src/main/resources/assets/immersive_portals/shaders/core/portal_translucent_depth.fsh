#version 150

// The depth IP decides a pass's portal areas against becomes the depth without translucents, so a portal shows through
// glass, water and particles in front of it; a translucent held item keeps its depth (IPIrisTranslucentDepth).

uniform sampler2D IPMainDepth;
uniform sampler2D IPPreTranslucentDepth;
uniform sampler2D IPBeforeHandDepth;

in vec2 texCoord;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    // the held item was drawn here: the main depth (already in the target) stays
    if (texelFetch(IPMainDepth, pixel, 0).r != texelFetch(IPBeforeHandDepth, pixel, 0).r) {
        discard;
    }
    gl_FragDepth = texelFetch(IPPreTranslucentDepth, pixel, 0).r;
}
