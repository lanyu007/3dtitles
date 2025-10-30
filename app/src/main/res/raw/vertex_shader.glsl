#version 300 es

// Vertex shader for point cloud rendering

// Input attributes
in vec3 a_Position;    // Vertex position
in vec4 a_Color;       // Vertex color (RGBA)

// Output to fragment shader
out vec4 v_Color;

// Uniforms
uniform mat4 u_MVPMatrix;     // Model-View-Projection matrix
uniform float u_PointSize;     // Point size

void main() {
    // Transform vertex position
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);

    // Set point size
    gl_PointSize = u_PointSize;

    // Pass color to fragment shader
    v_Color = a_Color;
}