#version 150

// Where a laser beam is the nearest thing on screen, the depth IP decides its portal areas against becomes the depth
// behind it (the depth from before the pass's first beam), so a portal behind a beam shows (IPLaserIrisPortals).

uniform sampler2D IPMainDepth;
uniform sampler2D IPBeforeBeamsDepth;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float nearest = texelFetch(IPMainDepth, pixel, 0).r;
    // something drawn in front of the beam keeps its depth
    if (gl_FragCoord.z > nearest + 0.00001) {
        discard;
    }
    gl_FragDepth = texelFetch(IPBeforeBeamsDepth, pixel, 0).r;
}
