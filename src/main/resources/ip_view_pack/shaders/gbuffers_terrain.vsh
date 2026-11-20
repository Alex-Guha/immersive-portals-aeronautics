#version 120

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying float viewDistance;

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
#ifdef IP_SEPARATE_AO
    // the player's pack takes ambient occlusion in vertex alpha
    glcolor.rgb *= glcolor.a;
    glcolor.a = 1.0;
#endif
#ifdef IP_NO_DIRECTIONAL_SHADING
    // the player's pack shades faces itself, so the meshes carry none: vanilla's face shading
    vec3 n = gl_Normal;
    if (dot(n, n) > 0.25) {
        n = normalize(n);
        glcolor.rgb *= n.x * n.x * 0.6 + n.z * n.z * 0.8 + n.y * n.y * (n.y > 0.0 ? 1.0 : 0.5);
    }
#endif
    viewDistance = length((gl_ModelViewMatrix * gl_Vertex).xyz);
}
