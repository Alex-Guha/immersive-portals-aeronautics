#version 120

uniform mat4 dhProjection;

varying vec2 lmcoord;
varying vec4 glcolor;
varying float viewDistance;

void main() {
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    gl_Position = dhProjection * viewPos;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    viewDistance = length(viewPos.xyz);
}
