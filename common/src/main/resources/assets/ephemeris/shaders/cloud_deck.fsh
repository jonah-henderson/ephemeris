#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(std140) uniform DeckInfo {
    vec4 LowTone;
    vec4 HighTone;
    vec4 SampleAndRoil;
    vec4 Extent;
};

uniform sampler2D Sampler0;

layout(location = 0) in float faceBrightness;
layout(location = 1) in vec2 worldSample;
layout(location = 2) in vec2 acrossTheSlab;
layout(location = 3) in float eyeDistance;

layout(location = 0) out vec4 fragColor;

// A pixel this faint is sky. Vanilla's own test on its cloud picture is `alpha < 10` of 255.
const float SOLID_ENOUGH = 10.0 / 255.0;

// The four sine amplitudes below, summed — what the total is divided by to land back in -1..1. Derived,
// so it must be updated with them; the frequencies themselves are free.
const float SINE_AMPLITUDE_SUM = 1.0 + 0.7 + 0.5 + 0.4;

// Where the deck starts thinning. Far enough out that the fade reads as distance rather than as a ring.
const float RIM_BEGINS = 0.72;

// The roil: a sum of drifting sines, in 0..1. **Shading, never coverage** — it picks a tone between the
// deck's two, and cuts nothing away. Evaluated per fragment, which is the change from the renderer this
// replaces: that one could only afford it once per 32-block vertex, so the roil was a coarse interpolated
// wash.
float roilAt(vec2 world, float drifted) {
    float value = sin(world.x * 0.018 + drifted);
    value += 0.7 * sin(world.y * 0.021 - drifted * 0.9);
    value += 0.5 * sin((world.x + world.y) * 0.012 + drifted * 1.4);
    value += 0.4 * cos((world.x - world.y) * 0.015 - drifted * 0.7);
    return clamp((value / SINE_AMPLITUDE_SUM) * 0.5 + 0.5, 0.0, 1.0);
}

void main() {
    float drifted = SampleAndRoil.z;
    float contrast = SampleAndRoil.w;
    float cellBlocks = Extent.y;
    float cutFromTexture = Extent.z;
    float scroll = Extent.w;

    // **Where the deck has cloud and where it has sky** — nothing to do with the roil below, which only
    // decides the tone of the cloud that is here. Vanilla's own grid: the picture is read a cell at a time
    // rather than smoothly, which is what gives clouds their blocky edge instead of an airbrushed one.
    // Wrapping is the sampler's, so the picture tiles across the world the way vanilla's does.
    if (cutFromTexture > 0.5) {
        vec2 cell = floor(vec2(worldSample.x + scroll, worldSample.y) / cellBlocks);
        // Asked of the texture rather than passed in, so a consumer's own picture may be any size it likes
        // and still tile correctly. Wrapping is the sampler's.
        if (texture(Sampler0, cell / vec2(textureSize(Sampler0, 0))).a < SOLID_ENOUGH) discard;
    }

    float roil = roilAt(worldSample, drifted);
    float toned = clamp((roil - 0.5) * contrast + 0.5, 0.0, 1.0);

    vec3 tone = mix(LowTone.rgb, HighTone.rgb, toned) * faceBrightness;
    // Near-opaque, and a touch more so where the roil is thick.
    float alpha = 0.92 + 0.08 * roil;

    // **Faded to a disc**, the way vanilla's clouds go: the slab is square, so without this its corners
    // reach half again as far as its edges and the deck ends on four straight lines with a horizon behind
    // them. Fading on radius hides the geometry — the edge midpoints sit at 1.0 and the corners past it,
    // so everything outside the inscribed circle is gone before it can be seen.
    // Taken here rather than in the vertex stage, which is the whole of why this works — see `cloud_deck.vsh`.
    float reach = length(acrossTheSlab);
    alpha *= 1.0 - smoothstep(RIM_BEGINS, 1.0, reach);

    // **Faded out by the fog, not toward its colour** — vanilla's own clouds do exactly this, and it is the
    // right one of the two: a deck is a translucent thing hanging in front of the sky, so what the fog takes
    // from it should leave the sky behind it showing rather than paint a cloud-shaped patch of fog colour.
    // `FogCloudsEnd` is the same number vanilla hands its own clouds — the water fog end when you are under
    // it, and whatever `CLOUD_FOG_END_DISTANCE` a blizzard or a column of sand has asked for otherwise.
    alpha *= 1.0 - linear_fog_value(eyeDistance, 0.0, FogCloudsEnd);
    if (alpha <= 0.0) discard;

    fragColor = vec4(tone, alpha) * ColorModulator;
}
