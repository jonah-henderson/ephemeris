#version 330
// **Required since 26.3, which compiles these to SPIR-V.** Every `in` and `out` crossing a stage needs an
// explicit location under it, and `#moj_import` is gone in favour of `#include`.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// One deck's slab: a unit box, scaled and lifted into place by ModelViewMat. Position carries the local
// corner and Color carries only a face brightness — the roil itself is left to the fragment stage, which
// is the whole reason this pipeline exists.
//
// The locations are the vertex format's own order — POSITION_COLOR, so position then colour.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(std140) uniform DeckInfo {
    vec4 LowTone;
    vec4 HighTone;
    // xy: where this deck reads the noise field, in world units, camera included.
    // z: the already-drifted time it reads at.  w: how hard the roil is pushed toward its extremes.
    vec4 SampleAndRoil;
    // x: half the slab's width in blocks, which turns a unit corner back into a world distance.
    // y: how wide one cell of the cloud picture is, in blocks.  z: whether to cut holes at all.
    // w: how far the picture has scrolled, in blocks.
    vec4 Extent;
};

layout(location = 0) out float faceBrightness;
layout(location = 1) out vec2 worldSample;
// Where on the slab this fragment is, in local units — `±1` at the edges (`Blaze3dSkyCanvas.buildSlab`).
// The fragment stage takes its length to fade the square slab into a disc.
//
// **The position is interpolated and the distance taken there, never the other way round.** Every vertex
// of the slab is a *corner*, so a `length` computed here is `1.41` at all four corners of every face —
// and interpolating a constant gives that same 1.41 across the whole face, which is past the far end of
// the fade. Every fragment of every deck was discarded and the Spire had no clouds at all. Scaling the
// number was tried first and could not have worked: the fault is the order of the two operations, not
// their units (Jonah, 2026-08-06 and 2026-08-08, walked).
layout(location = 2) out vec2 acrossTheSlab;

// How far this corner stands from the eye, in blocks, for the fragment stage to fade against the fog.
//
// **Taken after ModelViewMat and not before.** `Position` is a unit corner; the matrix is what scales it
// out to the deck's half-width, lifts it to the deck's height above the eye, and turns it into the
// camera's view — and a rotation does not change a length, so the view-space position's own length is the
// distance from the eye. Measuring the unit corner instead would have said `1.73` for every vertex of
// every deck at every height.
layout(location = 3) out float eyeDistance;

void main() {
    vec4 eyeRelative = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * eyeRelative;
    eyeDistance = fog_spherical_distance(eyeRelative.xyz);

    faceBrightness = Color.r;

    // Where in the world this corner reads the roil. Position is a unit corner, so scaling it back up by
    // the slab's half-width and adding the deck's offset — which already carries the camera — lands in
    // world space. That is what keeps the pattern still as the player walks through it, rather than
    // dragging along with them.
    worldSample = Position.xz * Extent.x + SampleAndRoil.xy;
    acrossTheSlab = Position.xz;
}
