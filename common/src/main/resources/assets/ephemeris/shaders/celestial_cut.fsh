#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;

out vec4 fragColor;

// **Where vanilla's celestial sprites stop being background and start being a body.**
//
// They carry no alpha at all -- indexed colour, no `tRNS` -- and what surrounds the body is not even flat
// black. A moon sits in a dark *blue* night-sky gradient climbing to `rgb(16,23,40)`; the sun sits in a
// yellow glow ramping to `rgb(40,40,16)`. Vanilla never notices, drawing both additively where dark
// contributes nothing. Drawn *covering*, that background is painted over the sky as an enormous dark square.
//
// Every one of the ten sprites has a wide empty band in its luminance, and this is the middle of the
// narrowest: nothing sits between the sun's halo at 37.3 and the new moon's faint disc at 46.8. It is
// therefore a gap rather than a tuned edge, and `CelestialTextureCheck` measures each sprite's own gap and
// holds this inside it.
const float BODY_ABOVE = 42.0 / 255.0;

const vec3 GREY = vec3(0.299, 0.587, 0.114);

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0 || dot(color.rgb, GREY) < BODY_ABOVE) {
        discard;
    }
    fragColor = color * ColorModulator;
}
