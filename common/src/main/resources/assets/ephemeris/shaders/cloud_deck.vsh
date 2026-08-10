#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// One deck's slab: a unit box, scaled and lifted into place by ModelViewMat. Position carries the local
// corner and Color carries only a face brightness — the roil itself is left to the fragment stage, which
// is the whole reason this pipeline exists.
in vec3 Position;
in vec4 Color;

layout(std140) uniform DeckInfo {
    vec4 LowTone;
    vec4 HighTone;
    // xy: where this deck reads the noise field, in world units, camera included.
    // z: the already-drifted time it reads at.  w: how hard the roil is pushed toward its extremes.
    vec4 SampleAndRoil;
    // x: half the slab's width in blocks, which turns a unit corner back into a world distance.
    vec4 Extent;
};

out float faceBrightness;
out vec2 worldSample;
// Where on the slab this fragment is, in local units — `±1` at the edges (`Blaze3dSkyCanvas.buildSlab`).
// The fragment stage takes its length to fade the square slab into a disc.
//
// **The position is interpolated and the distance taken there, never the other way round.** Every vertex
// of the slab is a *corner*, so a `length` computed here is `1.41` at all four corners of every face —
// and interpolating a constant gives that same 1.41 across the whole face, which is past the far end of
// the fade. Every fragment of every deck was discarded and the Spire had no clouds at all. Scaling the
// number was tried first and could not have worked: the fault is the order of the two operations, not
// their units (Jonah, 2026-08-06 and 2026-08-08, walked).
out vec2 acrossTheSlab;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    faceBrightness = Color.r;

    // Where in the world this corner reads the roil. Position is a unit corner, so scaling it back up by
    // the slab's half-width and adding the deck's offset — which already carries the camera — lands in
    // world space. That is what keeps the pattern still as the player walks through it, rather than
    // dragging along with them.
    worldSample = Position.xz * Extent.x + SampleAndRoil.xy;
    acrossTheSlab = Position.xz;
}
