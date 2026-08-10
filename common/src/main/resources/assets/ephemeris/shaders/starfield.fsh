#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 starColor;

out vec4 fragColor;

void main() {
    fragColor = starColor * ColorModulator;
}
