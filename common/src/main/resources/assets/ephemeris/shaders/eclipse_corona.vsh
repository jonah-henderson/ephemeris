#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// One quad round the sun, placed as a body is; the corona is worked out per pixel in the fragment stage.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

// From the sun's centre, -1..1 across the quad.
layout(location = 0) out vec2 fromTheCentre;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    fromTheCentre = UV0 * 2.0 - 1.0;
}
