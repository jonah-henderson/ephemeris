#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// A flat unit grid — `Position` is `(along, up, 0)` and carries no world meaning at all. Where the sheet
// actually stands is computed here, from uniforms, so one static mesh serves every curtain at every
// distance and the fold costs no rebuild.
in vec3 Position;
in vec2 UV0;

layout(std140) uniform AuroraInfo {
    // x: how present this curtain is, 0..1.  y: the folded time it is read at.
    // z: what share of its length actually glows.  w: unused.
    vec4 Shape;
    // x: how far the sheet snakes, in kilometres.  y: how fine the rays are.
    // z: this curtain's own phase.  w: how far the top leans past the bottom, in kilometres.
    vec4 Fold;
    // x: how far away the arc stands.  y: half its length.  z: the altitude it starts at.
    // w: the altitude it reaches. All in kilometres.
    vec4 Arc;
};

out vec2 acrossTheSheet;

// How many sky units a kilometre is. Arbitrary — the sky pass writes no depth and nothing else is drawn at
// this scale — but it must be small enough that a 700km arc stays inside the projection's far plane.
const float UNITS_PER_KM = 0.16;

// How much the arc bows away at its ends. A real one is a circle thousands of kilometres across, so over the
// stretch anybody can see it is very nearly straight, with just enough curve that the ends fall away.
const float BOW = 0.22;

/**
 * How far the sheet has snaked sideways at this point along it, in kilometres.
 *
 * **Horizontal, which is the whole correction.** A real curtain is a thin vertical sheet that meanders in
 * *plan* — the folds and curls are bends in its ground track, seen edge-on as bright vertical creases. The
 * band this replaced undulated *vertically* instead, which is a shape no aurora has.
 */
float snakeAt(float along, float drifted, float reach, float phase) {
    float bend = sin(along * 3.1 + drifted * 0.13 + phase);
    bend += 0.55 * sin(along * 7.7 - drifted * 0.21 + phase * 1.7);
    bend += 0.30 * cos(along * 13.3 + drifted * 0.09 - phase * 2.3);

    // **How hard it is folding just here.** A real arc is not evenly wavy along its whole length: it runs
    // nearly straight for a stretch and then knots into a tight curl, and it is the *contrast* between the
    // two that reads as an aurora rather than as a ribbon. An even wave is a ribbon however deep it is.
    float curling = 0.25 + 0.75 * pow(0.5 + 0.5 * sin(along * 2.3 - drifted * 0.06 + phase * 1.1), 2.0);
    return bend * reach * curling;
}

void main() {
    float along = UV0.x;
    float up = UV0.y;

    float halfLength = Arc.y;
    // Along the arc, which runs across the view — east-west, when the pole is due north.
    float acrossKm = (along - 0.5) * 2.0 * halfLength;
    // Straight up: the sheet stands along the field lines, which are near enough vertical up here. This is
    // what makes an arc passing overhead converge into a corona — parallel columns, seen in perspective.
    float upKm = mix(Arc.z, Arc.w, up);
    // And away from the viewer: the arc's own distance, its bow, and the snake it is making just now.
    float fromEnd = acrossKm / max(halfLength, 1.0);
    // **The top leans past the bottom**, because the field lines it stands along are not quite vertical —
    // about seventy-eight degrees at auroral latitudes, so a couple of hundred kilometres of height carries
    // the crown some tens of kilometres poleward. Without it the sheet is a flat extrusion, and a flat
    // extrusion is exactly what "ribbony" means.
    float leaning = Fold.w * up;
    float awayKm = Arc.x + BOW * halfLength * fromEnd * fromEnd + leaning
        + snakeAt(along, Shape.y, Fold.x, Fold.z);

    // North is -Z, which is where an arc with no bearing stands.
    vec3 standing = vec3(acrossKm, upKm, -awayKm) * UNITS_PER_KM;
    gl_Position = ProjMat * ModelViewMat * vec4(standing, 1.0);
    acrossTheSheet = vec2(along, up);
}
