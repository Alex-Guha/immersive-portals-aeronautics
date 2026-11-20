#version 150

uniform sampler2D Sampler0;
uniform vec3 PortalAxisW;
uniform vec3 PortalAxisH;
// the sprite's u0, v0, u1, v1 in the block atlas
uniform vec4 SpriteBounds;

in vec3 portalLocal;

out vec4 fragColor;

// one tile of the sprite per block along the portal's axes, translucent over what is behind the portal as the
// block is (the texture's own alpha, about 0.75), dimmed to about the block's own light level (11) in vanilla's lightmap
void main() {
    vec2 tile = fract(vec2(dot(portalLocal, PortalAxisW), -dot(portalLocal, PortalAxisH)));
    vec2 uv = mix(SpriteBounds.xy, SpriteBounds.zw, tile);
    vec4 color = textureLod(Sampler0, uv, 0.0);
    fragColor = vec4(color.rgb * 0.75, color.a);
}
