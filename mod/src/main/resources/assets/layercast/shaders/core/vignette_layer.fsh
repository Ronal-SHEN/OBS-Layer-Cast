#version 330

// Vignette for a transparent layer (Minecraft 26.1 - 26.2 uniform layout). Vanilla darkens what is below with
// dst * (1 - src); on a transparent layer that has nothing to darken, so the same darkening is written as black with
// alpha = strength, which gives the identical result once the layer is composited over the game.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec3 darkening = texture(Sampler0, texCoord0).rgb * vertexColor.rgb * ColorModulator.rgb;
    // A tinted vignette (world border warning) darkens channels unequally; a single alpha keeps the strongest.
    float strength = max(darkening.r, max(darkening.g, darkening.b));
    if (strength == 0.0) {
        discard;
    }
    fragColor = vec4(0.0, 0.0, 0.0, strength);
}
