#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
// UV0 carries the star's twinkle rather than a texture coordinate: (phase, rate). The format has no
// better-named pair of per-vertex floats, and all four corners of a star carry the same values.
in vec2 UV0;
in vec4 Color;

layout(std140) uniform StarfieldInfo {
    // x: the time the twinkle is read at, already wrapped. y: how far down a star dims at its faintest.
    vec4 TimeAndDip;
};

out vec4 starColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    float phase = UV0.x;
    float rate = UV0.y;
    float dip = TimeAndDip.y;

    float wave = 0.5 + 0.5 * sin(TimeAndDip.x * rate + phase);
    starColor = vec4(Color.rgb, Color.a * mix(dip, 1.0, wave));
}
