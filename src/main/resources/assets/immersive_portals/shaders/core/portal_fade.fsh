#version 150

// What was behind a fading global portal, blended back over its view (IPGlobalPortalFade)

uniform sampler2D DiffuseSampler;
uniform float FadeAlpha;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, FadeAlpha);
}
