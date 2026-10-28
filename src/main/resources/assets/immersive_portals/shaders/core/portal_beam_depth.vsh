#version 150

// A laser beam's geometry, placed as Simulated's laser shader places it (IPLaserIrisPortals.correctPortalDepth).

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
