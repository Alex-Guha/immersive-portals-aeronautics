#version 150

// portal_area, but fragments behind Distant Horizons' LODs of this pass are discarded (IPDhLodOcclusion): DH writes
// its LODs to Minecraft's colour buffer only, so the depth test alone lets a portal show over the LODs in front of it.

in vec4 vertexColor;
in vec3 ip_viewPos;

// DH's depth for this pass, its inverse projection, and (NDC z scale, NDC z offset, depth of "no LOD")
uniform sampler2D IPDhDepth;
uniform mat4 IPDhInverseProjection;
uniform vec3 IPDhDepthParams;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(IPDhDepth, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (all(lessThan(pixel, size))) {
        float depth = texelFetch(IPDhDepth, pixel, 0).r;
        if (depth != IPDhDepthParams.z) {
            vec2 ndcXY = (vec2(pixel) + 0.5) / vec2(size) * 2.0 - 1.0;
            vec4 lod = IPDhInverseProjection * vec4(ndcXY, depth * IPDhDepthParams.x + IPDhDepthParams.y, 1.0);
            float lodDistance = length(lod.xyz / lod.w);
            float areaDistance = length(ip_viewPos);
            // a block of slack (more far away) so a portal lying on an LOD face is not cut
            if (lodDistance < areaDistance - max(1.0, areaDistance * 0.002)) {
                discard;
            }
        }
    }
    fragColor = vertexColor;
}
