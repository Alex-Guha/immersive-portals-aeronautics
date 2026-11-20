#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 PortalOrigin;

out vec3 portalLocal;

// a portal view that is not drawn, shown with the nether portal texture: positions relative to the portal's origin
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    portalLocal = Position - PortalOrigin;
}
