#version 150

// portal_area with the view-space position passed on, for the cut behind Distant Horizons' LODs
// (IPDhLodOcclusion)

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec3 ip_viewPos;

// even this shader code will be transformed to add clipping

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vertexColor = Color;
    ip_viewPos = viewPos.xyz;
}
