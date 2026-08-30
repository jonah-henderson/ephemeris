#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform AuroraInfo {
    vec4 Shape;
    vec4 Fold;
    vec4 Arc;
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
// **Small, and that is measured rather than chosen.** An auroral form has a genuinely sharp lower border at
// about 105km — it is where the incoming electrons finally run out against thickening air — and it is the
// one hard line in the whole phenomenon.
const float HEM_SHARPNESS = 0.04;

// Where the long fade to the crown begins, as a share of **how high this column reaches**.
//
// The emission thins upward because the air does: green oxygen runs 100–150km and red oxygen 200–300km, far
// fainter. So the sheet is brightest low and trails away above.
const float CROWN_FADE_FROM = 0.30;

// How short the lowest columns are against the tallest, `0..1`.
//
// **This is what stops the top being a ruled line.** Every column reached exactly the same altitude before,
// so the sheet ended on a dead straight edge however folded its ground track was — which is the whole of
// what read as flat (Jonah, 2026-08-30, walked: "all the heights are completely equal"). A real form has a
// ragged crown: some rays shoot far above their neighbours and others barely clear the border.
const float SHORTEST_REACH = 0.32;

// How much of the brightness the vertical rays own.
const float RAY_SHARE = 0.45;

// The three combs' spacings, against `fineness`. Deliberately not simple fractions of one another: two combs
// at 1 and 1/2 beat in a pattern an eye picks out at once, which is the artificiality a single sine had.
const float MID_RAYS = 0.41;
const float COARSE_RAYS = 0.13;

// How far the raying itself comes and goes along the arc — stretches of hard striation and stretches of
// smooth light, so the whole length never reads as one texture.
const float PATCHINESS = 0.55;

/**
 * How high the column at this point along the arc reaches, `SHORTEST_REACH..1`.
 *
 * Two scales, and both matter. The broad wave gives whole stretches of arc that stand tall or low, which is
 * the silhouette an eye reads first. The fine one is tied to the ray spacing, so a bright ray is a *tall*
 * ray — which is not a trick but the physics: a brighter column is one more energetic electrons reached, and
 * they excite a longer stretch of air on the way down.
 */
float reachAt(float along, float drifted, float fineness, float phase) {
    float broad = 0.5 + 0.5 * sin(along * 4.3 - drifted * 0.11 + phase * 0.9);
    float fine = 0.5 + 0.5 * sin(along * fineness * 0.5 + 2.0 * sin(along * 17.0 + phase));
    return SHORTEST_REACH + (1.0 - SHORTEST_REACH) * (0.65 * broad + 0.35 * fine);
}

/**
 * The vertical rays, in 0..1.
 *
 * Three combs at spacings that are not simple fractions of one another, multiplied rather than summed so
 * that a gap in any one of them is a gap and the dark lanes come out uneven. The fine comb is
 * phase-modulated by a slower wave, which shears the rays rather than sliding them.
 */
float raysAt(float along, float drifted, float fineness, float phase) {
    float sheared = along * fineness + 3.0 * sin(along * 31.0 + drifted * 0.05 + phase);
    float fine = 0.5 + 0.5 * sin(sheared);
    float mid = 0.5 + 0.5 * sin(along * fineness * MID_RAYS - drifted * 0.09 + phase * 1.3);
    float coarse = 0.5 + 0.5 * sin(along * fineness * COARSE_RAYS + drifted * 0.04 - phase);
    float comb = fine * (0.55 + 0.45 * mid) * (0.7 + 0.3 * coarse);
    float patch = 0.5 + 0.5 * sin(along * 5.9 - drifted * 0.07 + phase * 0.7);
    return mix(1.0, comb, PATCHINESS + (1.0 - PATCHINESS) * patch);
}

void main() {
    float strength = Shape.x;
    float drifted = Shape.y;
    float glowing = Shape.z;
    float fineness = Fold.y;
    float phase = Fold.z;

    float along = acrossTheSheet.x;
    float up = acrossTheSheet.y;

    // **The sheet is the whole mesh now**, top to bottom, so there is no band to carve a curtain out of and
    // no edge for a fold to be clipped against. `up` is altitude directly.
    float hem = smoothstep(0.0, HEM_SHARPNESS, up);
    // The crown fades toward *this column's* own ceiling rather than the mesh's, which is what makes the
    // top ragged instead of ruled.
    float reach = reachAt(along, drifted, fineness, phase);
    float crown = 1.0 - smoothstep(CROWN_FADE_FROM * reach, reach, up);
    float body = hem * crown;

    // Both ends fade rather than stopping, so the arc runs out into the air instead of ending on a cut.
    float fromMiddle = abs(along - 0.5) * 2.0;
    float ends = 1.0 - smoothstep(glowing * ENDS_BEGIN, glowing, fromMiddle);
    if (ends <= 0.0) discard;

    // The rays fade out toward the crown with everything else, so they read as standing *in* the sheet.
    float rays = mix(1.0, raysAt(along, drifted, fineness, phase), RAY_SHARE * crown);

    float alpha = body * rays * ends * strength;
    if (alpha <= 0.0) discard;

    // Crown at 0, hem at 1 — the order the colours were written in, so what the ramp holds and what a report
    // says about it cannot drift apart.
    vec3 tone = texture(Sampler0, vec2(1.0 - up, 0.5)).rgb;

    fragColor = vec4(tone, alpha) * ColorModulator;
}
