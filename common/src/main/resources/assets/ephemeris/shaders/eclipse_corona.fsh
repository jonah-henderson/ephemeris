#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <ephemeris:noise.glsl>

layout(std140) uniform CoronaInfo {
    // x: the disc's radius, as a share of the quad's half-width.  y: how much of the corona shows, 0..1.
    // z: this corona's own place in the noise.  w: the bearing its equator lies along, in radians.
    vec4 Shape;
    // x: how many times farther than an eclipse's the light reaches.
    vec4 Reach;
};

layout(location = 0) in vec2 fromTheCentre;

layout(location = 0) out vec4 fragColor;

const float NOISE_TILE = 8192.0;

// How far round the noise each ring of it is read, which is how many features go round the sun: a handful of
// broad petals, and many fine streaks.
const float PETALS_ROUND = 2.2;
const float STREAKS_ROUND = 14.0;

// How far out the light falls to a third, in the disc's radii: the bright ring hugging the rim, and the
// streamers. Each petal reaches between the shortest and longest share of the streamers' reach.
const float RIM_REACH = 0.3;
const float STREAMER_REACH = 1.2;
const float SHORTEST_PETAL = 0.45;
const float LONGEST_PETAL = 1.6;

// How much shorter the streamers are over the poles than along the equator.
const float OVER_THE_POLES = 0.55;

// How much of the light is the rim's, and how deep the fine streaks cut into the streamers.
const float RIM_SHARE = 0.55;
const float STREAK_DEPTH = 0.35;

// Where the light starts fading to nothing before the quad's edge, so no edge of it is ever seen.
const float EDGE_FADE_FROM = 0.75;

void main() {
    float outward = length(fromTheCentre);
    float edge = 1.0 - smoothstep(EDGE_FADE_FROM, 1.0, outward);
    if (edge <= 0.0) discard;

    // Past the rim, in the disc's radii. Inside it is the sun's, and whatever covers the sun covers this too.
    float pastTheRim = max(outward / Shape.x - 1.0, 0.0) / Reach.x;

    // Read round a circle, so the noise closes on itself with no seam.
    float bearing = atan(fromTheCentre.y, fromTheCentre.x);
    vec2 aroundTheSun = vec2(cos(bearing), sin(bearing));
    vec2 period = vec2(NOISE_TILE);

    float petal = ephemerisFbm(aroundTheSun * PETALS_ROUND + vec2(Shape.z, 0.0), period, 3);
    float alongTheEquator = abs(cos(bearing - Shape.w));
    float reach = STREAMER_REACH
        * mix(SHORTEST_PETAL, LONGEST_PETAL, petal)
        * mix(OVER_THE_POLES, 1.0, alongTheEquator * alongTheEquator);

    float streaks = ephemerisFbm(aroundTheSun * STREAKS_ROUND + vec2(0.0, Shape.z), period, 2);
    float streamers = exp(-pastTheRim / reach) * (1.0 - STREAK_DEPTH + 2.0 * STREAK_DEPTH * streaks);
    float rim = exp(-pastTheRim / RIM_REACH);

    float light = (RIM_SHARE * rim + (1.0 - RIM_SHARE) * streamers) * edge * Shape.y;
    if (light <= 0.0) discard;

    fragColor = vec4(1.0, 1.0, 1.0, light) * ColorModulator;
}
