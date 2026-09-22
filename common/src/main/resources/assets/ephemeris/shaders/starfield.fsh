#version 330
// SPIR-V since 26.3: every stage-crossing declaration needs a location.
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec4 starColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = starColor * ColorModulator;
}
