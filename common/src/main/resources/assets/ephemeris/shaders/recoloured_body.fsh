#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// The body's sprite.
uniform sampler2D Sampler0;
// The palette, one row: what a texel `n/255` bright becomes, premultiplied — see `Palette`.
uniform sampler2D Sampler1;

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

// `Palette.SEEN_AS_*`: the levels `Palette.VANILLA_SUN_*` were measured with.
const vec3 SEEN_AS = vec3(0.2126, 0.7152, 0.0722);

// Lands each brightness on the middle of its own pixel of the row.
const float STEPS = 256.0;

// Below this a texel is glow rather than disc: vanilla's sun has nothing between its glow's brightest and
// its rim.
const float DISC_FROM = 0.5;

// `ColorModulator` carries the glow's strength in its colour and the fade in its alpha.
void main() {
    float brightness = dot(texture(Sampler0, texCoord0).rgb, SEEN_AS);
    float across = (brightness * (STEPS - 1.0) + 0.5) / STEPS;
    float strength = brightness < DISC_FROM ? ColorModulator.r : 1.0;
    fragColor = texture(Sampler1, vec2(across, 0.5)) * strength * ColorModulator.a;
}
