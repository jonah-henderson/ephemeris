#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The curtain's canvas: a band of the sky sphere, generous in both directions. Where the curtain actually
// is inside that band is the fragment stage's to decide — see `aurora.fsh`. Position carries the point on
// the sphere and UV0 carries where on the band it is, `0..1` across and `0..1` up.
in vec3 Position;
in vec2 UV0;

// Declared here as well as in the fragment stage, and unused. `cloud_deck` declares `DeckInfo` in both and
// `starfield` declares `StarfieldInfo` in the vertex stage alone; a block declared only in the fragment
// stage is the one arrangement of the three nothing here has ever shipped, and an aurora that draws nothing
// is not the place to find out whether it works. Cheaper to match the proven shape than to know.
layout(std140) uniform AuroraInfo {
    vec4 Shape;
    vec4 Fold;
};

out vec2 acrossTheBand;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    acrossTheBand = UV0;
}
