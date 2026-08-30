#version 330

#moj_import <minecraft:dynamictransforms.glsl>

layout(std140) uniform AuroraInfo {
    // x: how present the curtain is, 0..1.  y: the folded time it is read at.
    // z: what share of the band's width it crosses.  w: how tall it stands.
    vec4 Shape;
    // x: how far the fold wanders.  y: how fine the rays are.
    // z: this curtain's own phase, so several hung at once are not one curtain drawn twice.
    // w: how far up or down the band this one sits.
    vec4 Fold;
};

// The colour ramp, crown at 0 and hem at 1, already interpolated between the stops it was built from —
// see `Blaze3dSkyCanvas.rampOf`. One fetch, and no loop over however many colours were written.
uniform sampler2D Sampler0;

in vec2 acrossTheBand;

out vec4 fragColor;

// Where the band starts fading at its ends, as a share of the crossing it was given. Far enough in that the
// curtain tapers away rather than stopping on two vertical lines.
const float ENDS_BEGIN = 0.55;

// How sharp the lower edge is, as a share of the curtain's height. Small: an aurora's hem is its one hard
// line, and everything above it is diffuse.
const float HEM_SHARPNESS = 0.05;

// Where the long fade to the crown begins, and where it has finished.
const float CROWN_FADE_FROM = 0.22;
const float CROWN_FADE_TO = 1.0;

// How much of the brightness the vertical rays own. Enough to read as structure, not so much that the
// curtain becomes a comb.
const float RAY_SHARE = 0.45;

// The three combs' spacings, against `fineness`. Deliberately not simple fractions of one another: two
// combs at 1 and 1/2 beat in a pattern an eye picks out immediately, which is the artificiality a single
// sine had (Jonah, 2026-08-30, walked: "the dark bands are very very even").
const float MID_RAYS = 0.41;
const float COARSE_RAYS = 0.13;

// How far the raying itself comes and goes along the curtain — stretches of hard striation and stretches
// of smooth light, which is what stops the whole length reading as one texture.
const float PATCHINESS = 0.55;

/**
 * Where the curtain's middle lies at this point along the band, in the same 0..1 the band is measured in.
 *
 * Three sines that do not divide into one another, so the fold never settles into a repeat an eye can
 * follow — the same reason `WoundField` picks its two periods the way it does.
 */
float middleAt(float along, float drifted, float wander, float phase) {
    float fold = sin(along * 3.1 + drifted * 0.13 + phase);
    fold += 0.55 * sin(along * 7.7 - drifted * 0.21 + phase * 1.7);
    fold += 0.30 * cos(along * 13.3 + drifted * 0.09 - phase * 2.3);
    return 0.5 + fold * wander * 0.12;
}

/**
 * The vertical rays, in 0..1.
 *
 * **Three combs and an envelope, where one sine used to be.** A single sine gives perfectly even bands with
 * perfectly even gaps, which is the one thing a real curtain never has — it read as corduroy. Combining
 * spacings that are not simple fractions of one another breaks the regularity outright, and the slow
 * envelope on top means the raying itself fades in and out along the length, so some stretches are hard
 * striation and others nearly smooth.
 *
 * The fine comb is also phase-modulated by a slower wave, which shears the rays rather than sliding them —
 * they lean and crowd the way a real curtain's do instead of marching sideways.
 */
float raysAt(float along, float drifted, float fineness, float phase) {
    float sheared = along * fineness + 3.0 * sin(along * 31.0 + drifted * 0.05 + phase);
    float fine = 0.5 + 0.5 * sin(sheared);
    float mid = 0.5 + 0.5 * sin(along * fineness * MID_RAYS - drifted * 0.09 + phase * 1.3);
    float coarse = 0.5 + 0.5 * sin(along * fineness * COARSE_RAYS + drifted * 0.04 - phase);

    // Multiplied rather than summed: a gap in any one of them is a gap, which gives the dark lanes their
    // uneven widths. Summing would average them back into a wash.
    float comb = fine * (0.55 + 0.45 * mid) * (0.7 + 0.3 * coarse);

    // And how *rayed* this stretch is at all, so the striation is a thing the curtain does in places.
    float patch = 0.5 + 0.5 * sin(along * 5.9 - drifted * 0.07 + phase * 0.7);
    return mix(1.0, comb, PATCHINESS + (1.0 - PATCHINESS) * patch);
}

void main() {
    float strength = Shape.x;
    float drifted = Shape.y;
    float crossing = Shape.z;
    float standing = Shape.w;
    float wander = Fold.x;
    float fineness = Fold.y;
    float phase = Fold.z;
    float lift = Fold.w;

    float along = acrossTheBand.x;
    float up = acrossTheBand.y;

    // **Both ends taper.** The band is a rectangle of sky and the curtain inside it is not: without this it
    // would end on two straight vertical edges with the horizon showing between them.
    float fromMiddle = abs(along - 0.5) * 2.0;
    float ends = 1.0 - smoothstep(crossing * ENDS_BEGIN, crossing, fromMiddle);
    if (ends <= 0.0) discard;

    // Where the curtain is, and how far up it this fragment stands. Outside it there is nothing at all —
    // the geometry is a generous canvas and this is what carves the shape out of it.
    float halfHeight = standing * 0.5;
    float middle = middleAt(along, drifted, wander, phase) + lift;
    float withinCurtain = (up - (middle - halfHeight)) / max(standing, 0.001);
    if (withinCurtain < 0.0 || withinCurtain > 1.0) discard;

    // A hard hem and a long diffuse crown, which is the one silhouette that reads as an aurora rather than
    // as a stripe: the bottom edge is where the light stops, and the top is where it runs out.
    float hem = smoothstep(0.0, HEM_SHARPNESS, withinCurtain);
    float crown = 1.0 - smoothstep(CROWN_FADE_FROM, CROWN_FADE_TO, withinCurtain);
    float body = hem * crown;

    // The rays fade out toward the crown with everything else, so they read as standing *in* the curtain.
    float rays = mix(1.0, raysAt(along, drifted, fineness, phase), RAY_SHARE * crown);

    float alpha = body * rays * ends * strength;
    if (alpha <= 0.0) discard;

    // Crown at 0, hem at 1 — the order the colours were written in, so what the ramp holds and what a
    // report says about it cannot drift apart.
    vec3 tone = texture(Sampler0, vec2(1.0 - withinCurtain, 0.5)).rgb;

    fragColor = vec4(tone, alpha) * ColorModulator;
}
