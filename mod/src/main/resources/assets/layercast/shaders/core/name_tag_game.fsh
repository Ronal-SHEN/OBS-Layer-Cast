#version 330

// The game layer without the name tags, drawn over a copy of the finished world where the name tag layer
// (NameTagSampler) has content. The first-person hand, drawn after the world over a cleared depth buffer, belongs to
// the game layer and stays; the name tag layer is erased there.
//
// Under a name tag, the world as it was right before the name tags were drawn (WorldSampler) is used. What vanilla
// draws after the name tags (water, glass, clouds, rain, particles) is missing from it, and cannot be recovered from
// the frame there: the text writes depth, so translucent things behind it are not even drawn. So its effect is
// estimated from the pixels around the tag, where it is the difference between the finished frame (FrameSampler)
// and the world before the name tags: the nearest such pixel in 8 directions (larger and larger steps, name tags
// close to the camera are large) is averaged. Exact where nothing is drawn later; a smooth approximation otherwise.
uniform sampler2D NameTagSampler;
uniform sampler2D FrameSampler;
uniform sampler2D WorldSampler;
uniform sampler2D HandDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

const int STEPS = 14;
const float DISTANCES[STEPS] = float[](1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 11.0, 16.0, 22.0, 32.0, 45.0, 64.0, 90.0, 128.0);
const vec2 DIRECTIONS[8] = vec2[](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
                                  vec2(0.7071, 0.7071), vec2(-0.7071, 0.7071), vec2(0.7071, -0.7071), vec2(-0.7071, -0.7071));

void main() {
    if (texture(NameTagSampler, texCoord).a == 0.0 || texture(HandDepthSampler, texCoord).r != CLEARED_DEPTH) {
        discard;
    }
    vec2 texel = 1.0 / vec2(textureSize(NameTagSampler, 0));
    vec3 change = vec3(0.0);
    float found = 0.0;
    for (int d = 0; d < 8; d++) {
        for (int i = 0; i < STEPS; i++) {
            vec2 uv = texCoord + DIRECTIONS[d] * texel * DISTANCES[i];
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                break;
            }
            if (texture(NameTagSampler, uv).a == 0.0) {
                if (texture(HandDepthSampler, uv).r == CLEARED_DEPTH) {
                    change += texture(FrameSampler, uv).rgb - texture(WorldSampler, uv).rgb;
                    found += 1.0;
                }
                break;
            }
        }
    }
    vec3 world = texture(WorldSampler, texCoord).rgb;
    if (found > 0.0) {
        world = clamp(world + change / found, 0.0, 1.0);
    }
    fragColor = vec4(world, 1.0);
}
