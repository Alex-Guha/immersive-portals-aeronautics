#version 120

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying float viewDistance;

void main() {
    vec4 color = texture2D(gtexture, texcoord) * glcolor;
    if (color.a < 0.1) {
        discard;
    }
    color.rgb *= texture2D(lightmap, lmcoord).rgb;
    float fog = clamp((viewDistance - fogStart) / max(fogEnd - fogStart, 0.001), 0.0, 1.0);
    color.rgb = mix(color.rgb, fogColor, fog);
/* DRAWBUFFERS:0 */
    gl_FragData[0] = color;
}
