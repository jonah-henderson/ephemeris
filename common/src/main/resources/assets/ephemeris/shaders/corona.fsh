#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Premultiplied, from vanilla's `position_color.vsh`.
layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// Vanilla's `position_color.fsh` without its discard at zero alpha: a streamer that is pure light hides
// nothing behind it, and its alpha is nought from root to tip.
void main() {
    fragColor = vertexColor * ColorModulator;
}
