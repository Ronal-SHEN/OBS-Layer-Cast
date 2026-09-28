#version 330

// Erases the name tag layer where the first-person hand, drawn after the world over a cleared depth buffer, covers it.
uniform sampler2D HandDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    if (texture(HandDepthSampler, texCoord).r == CLEARED_DEPTH) {
        discard;
    }
    fragColor = vec4(0.0);
}
