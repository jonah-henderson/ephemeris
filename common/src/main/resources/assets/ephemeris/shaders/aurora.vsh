#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The curtain's canvas: a band of the sky sphere, generous in both directions. Where the curtain actually
// is inside that band is the fragment stage's to decide — see `aurora.fsh`. Position carries the point on
// the sphere and UV0 carries where on the band it is, `0..1` across and `0..1` up.
in vec3 Position;
in vec2 UV0;

out vec2 acrossTheBand;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    acrossTheBand = UV0;
}
