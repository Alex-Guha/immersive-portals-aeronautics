#version 120

uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float dhFarPlane;

varying vec2 lmcoord;
varying vec4 glcolor;
varying float viewDistance;

void main() {
    vec4 color = glcolor;
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    // LODs fade into the fog colour towards the end of Distant Horizons' range only
    float fog = smoothstep(0.5 * dhFarPlane, dhFarPlane, viewDistance);
    color.rgb = mix(color.rgb, fogColor, fog);
/* DRAWBUFFERS:0 */
    gl_FragData[0] = color;
}
