#version 300 es

precision mediump float;

// Fragment shader for point cloud rendering

// Input from vertex shader
in vec4 v_Color;

// Output color
out vec4 fragColor;

void main() {
    // Calculate distance from point center for circular points
    vec2 coord = gl_PointCoord - vec2(0.5);
    float dist = length(coord);

    // Discard fragments outside circle for smooth circular points
    if (dist > 0.5) {
        discard;
    }

    // Output the color
    fragColor = v_Color;

    // Optional: Add soft edges
    // float alpha = 1.0 - smoothstep(0.4, 0.5, dist);
    // fragColor = vec4(v_Color.rgb, v_Color.a * alpha);
}