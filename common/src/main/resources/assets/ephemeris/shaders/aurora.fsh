#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <ephemeris:noise.glsl>

layout(std140) uniform AuroraInfo {
    vec4 Shape;
    vec4 Fold;
    vec4 Arc;
    // x: how far the crown leans past the hem, in kilometres. The rest is spare.
    vec4 Lean;
};

// The colour ramp, crown at 0 and hem at 1, already interpolated between the stops it was built from —
// see `Blaze3dSkyCanvas.rampOf`. One fetch, and no loop over however many colours were written.
uniform sampler2D Sampler0;

in vec2 acrossTheSheet;

out vec4 fragColor;

// Where the arc starts fading toward its ends, as a share of the length it was given. Real ones fade out
// with distance rather than stopping, so this stands in for the air between.
const float ENDS_BEGIN = 0.45;

// How sharp the lower edge is, as a share of the sheet's height.
//
// **Small, and measured rather than chosen.** An auroral form has a genuinely sharp lower border at about
// 105km — it is where the incoming electrons finally run out against thickening air — and it is the one
// hard line in the whole phenomenon.
const float HEM_SHARPNESS = 0.04;

// Where the long fade to the crown begins, as a share of how high this column reaches.
const float CROWN_FADE_FROM = 0.30;

// How short the lowest columns are against the tallest, `0..1`.
const float SHORTEST_REACH = 0.32;

// How much of the brightness the vertical rays own.
const float RAY_SHARE = 0.5;

// How wide a band of the noise becomes a lit ray. Narrow, so bright rays stand in wide dim lanes rather
// than the whole thing being a wash.
const float RAY_FROM = 0.42;
const float RAY_TO = 0.78;

// How much the raying comes and goes along the arc.
const float PATCHINESS = 0.45;

// How faint a column can flare down to.
const float FAINTEST_FLARE = 0.25;

// The field's own repeat, and how fast each thing walks through time. Every one of these must divide the
// wrapped drift — see `Blaze3dSkyCanvas.AURORA_TIME_WRAP`.
const float NOISE_TILE = 8192.0;
const float TIME_WRAP = 1000.0;
const float REACH_ALONG = 7.0;
const float REACH_THROUGH_TIME = 0.06;
const float RAYS_THROUGH_TIME = 0.1;
const float PATCH_ALONG = 3.5;
const float PATCH_THROUGH_TIME = 0.04;
const float FLARE_ALONG = 11.0;

/**
 * How high the column at this point along the arc reaches, `SHORTEST_REACH..1`.
 *
 * **This is what stops the top being a ruled line.** Every column reached the same altitude once, so the
 * sheet ended on a dead straight edge however folded its ground track was.
 *
 * Two scales and both matter: the broad octaves give whole stretches standing tall or low, and the fine
 * ones give ray-by-ray raggedness. Noise rather than waves, because a wave gives every third ray the same
 * height as the last.
 */
float reachAt(float along, float drifted, float fineness, float phase) {
    vec2 at = vec2(along * REACH_ALONG + phase, drifted * REACH_THROUGH_TIME);
    vec2 period = vec2(NOISE_TILE, TIME_WRAP * REACH_THROUGH_TIME);
    return SHORTEST_REACH + (1.0 - SHORTEST_REACH) * ephemerisFbm(at, period, 4);
}

/**
 * The vertical rays, in 0..1 — irregular in spacing, in width and in brightness.
 *
 * A comb of sines gives evenly spaced lanes of even width, which is the corduroy this replaced. Threshold a
 * noise field instead and the lanes come out at no spacing at all: some crowded, some wide apart, some
 * bright and narrow, some barely there.
 */
float raysAt(float along, float drifted, float fineness, float phase) {
    vec2 at = vec2(along * fineness * 0.5 + phase, drifted * RAYS_THROUGH_TIME);
    float lanes = smoothstep(RAY_FROM, RAY_TO, ephemerisFbm(at, vec2(NOISE_TILE, TIME_WRAP * RAYS_THROUGH_TIME), 3));

    // And how *rayed* this stretch is at all, so striation is something the curtain does in places.
    vec2 patchAt = vec2(along * PATCH_ALONG + phase * 0.6, drifted * PATCH_THROUGH_TIME);
    float patch = ephemerisFbm(patchAt, vec2(NOISE_TILE, TIME_WRAP * PATCH_THROUGH_TIME), 2);
    return mix(1.0, lanes, PATCHINESS + (1.0 - PATCHINESS) * patch);
}

/**
 * How brightly this column is burning just now, `FAINTEST_FLARE..1`.
 *
 * **The curtain's own life, as against its drift.** Everything else here moves the *shape* — the sheet
 * meanders, the rays shear along with it — so the whole thing slid about like one object. A real display
 * pulses: columns surge and die where they stand, faster than the form itself travels, and it is that
 * flickering rather than the motion that makes it look alive.
 *
 * Walked faster through time than anything else for exactly that reason.
 */
float flareAt(float along, float drifted, float phase) {
    vec2 at = vec2(along * FLARE_ALONG + phase * 1.9, drifted * Fold.w);
    float pulse = ephemerisFbm(at, vec2(NOISE_TILE, TIME_WRAP * Fold.w), 3);
    return FAINTEST_FLARE + (1.0 - FAINTEST_FLARE) * smoothstep(0.15, 0.85, pulse);
}

void main() {
    float strength = Shape.x;
    float drifted = Shape.y;
    float glowing = Shape.z;
    float fineness = Fold.y;
    float phase = Fold.z;

    float along = acrossTheSheet.x;
    float up = acrossTheSheet.y;

    // **Ordered cheapest first, and every stage lets go before the next costs anything.** Four fields of
    // noise are the price of not looking like arithmetic, and most of this mesh is not curtain at all — the
    // ends, and everything above the crown. Sampling them first and testing afterwards paid for the whole
    // sheet at the density of its brightest part.

    // Both ends fade rather than stopping, so the arc runs out into the air instead of ending on a cut.
    float fromMiddle = abs(along - 0.5) * 2.0;
    float ends = 1.0 - smoothstep(glowing * ENDS_BEGIN, glowing, fromMiddle);
    if (ends <= 0.0) discard;

    // The sheet is the whole mesh, top to bottom, so `up` is altitude directly and there is no band to
    // carve a curtain out of — nor any edge for a fold to be clipped against.
    float hem = smoothstep(0.0, HEM_SHARPNESS, up);
    if (hem <= 0.0) discard;
    float reach = reachAt(along, drifted, fineness, phase);
    float crown = 1.0 - smoothstep(CROWN_FADE_FROM * reach, reach, up);
    float body = hem * crown;
    if (body <= 0.0) discard;

    // The rays fade out toward the crown with everything else, so they read as standing *in* the sheet.
    float rays = mix(1.0, raysAt(along, drifted, fineness, phase), RAY_SHARE * crown);

    float alpha = body * rays * ends * flareAt(along, drifted, phase) * strength;
    if (alpha <= 0.0) discard;

    // Crown at 0, hem at 1 — the order the colours were written in, so what the ramp holds and what a report
    // says about it cannot drift apart.
    vec3 tone = texture(Sampler0, vec2(1.0 - up, 0.5)).rgb;

    fragColor = vec4(tone, alpha) * ColorModulator;
}
