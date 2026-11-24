#version 120

uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float dhFarPlane;
uniform float far;

varying vec2 lmcoord;
varying vec4 glcolor;
varying float viewDistance;

void main() {
    // no LOD where vanilla terrain is drawn (Iris's far: in a portal view, how far the far side's terrain reaches),
    // starting a chunk inside it as packs do: nearer LODs showed wherever the pass cut terrain away, such as around a
    // camera that sits in the ground under a floor portal
    if (viewDistance < far - 16.0) {
        discard;
    }
    vec4 color = glcolor;
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    // LODs fade into the fog colour towards the end of Distant Horizons' range only
    float fog = smoothstep(0.5 * dhFarPlane, dhFarPlane, viewDistance);
    color.rgb = mix(color.rgb, fogColor, fog);
/* DRAWBUFFERS:0 */
    gl_FragData[0] = color;
}
