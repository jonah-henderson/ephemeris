// OKLCH in a shader, as `co.voik.ephemeris.Oklch` is on the CPU: lightness as the eye sees it, chroma, and hue in turns
// round the wheel. Built on Björn Ottosson's OKLab (https://bottosson.github.io/posts/oklab/), whose matrices are
// below as he published them, written column by column as GLSL reads a mat3.

const float FULL_TURN_RADIANS = 6.28318530718;

// sRGB's transfer curve, as the standard gives it: linear near black, a 2.4 power above.
vec3 srgbDecoded(vec3 channel) {
    return mix(channel / 12.92, pow((channel + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), channel));
}

vec3 srgbEncoded(vec3 linear) {
    vec3 held = clamp(linear, 0.0, 1.0);
    return mix(held * 12.92, 1.055 * pow(held, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), held));
}

const mat3 LINEAR_SRGB_TO_CONES = mat3(
    0.4122214708, 0.2119034982, 0.0883024619,
    0.5363325363, 0.6806995451, 0.2817188376,
    0.0514459929, 0.1073969566, 0.6299787005
);
const mat3 CONES_TO_OKLAB = mat3(
    0.2104542553, 1.9779984951, 0.0259040371,
    0.7936177850, -2.4285922050, 0.7827717662,
    -0.0040720468, 0.4505937099, -0.8086757660
);
const mat3 OKLAB_TO_CONES = mat3(
    1.0, 1.0, 1.0,
    0.3963377774, -0.1055613458, -0.0894841775,
    0.2158037573, -0.0638541728, -1.2914855480
);
const mat3 CONES_TO_LINEAR_SRGB = mat3(
    4.0767416621, -1.2684380046, -0.0041960863,
    -3.3077115913, 2.6097574011, -0.7034186147,
    0.2309699292, -0.3413193965, 1.7076147010
);

// x lightness, y chroma, z hue in turns.
vec3 oklchOf(vec3 colour) {
    vec3 lab = CONES_TO_OKLAB * pow(LINEAR_SRGB_TO_CONES * srgbDecoded(colour), vec3(1.0 / 3.0));
    return vec3(lab.x, length(lab.yz), fract(atan(lab.z, lab.y) / FULL_TURN_RADIANS));
}

vec3 linearFromOklch(vec3 oklch) {
    float angle = oklch.z * FULL_TURN_RADIANS;
    vec3 cones = OKLAB_TO_CONES * vec3(oklch.x, oklch.y * cos(angle), oklch.y * sin(angle));
    return CONES_TO_LINEAR_SRGB * (cones * cones * cones);
}

bool fitsSrgb(vec3 linear) {
    return all(greaterThanEqual(linear, vec3(-0.0005))) && all(lessThanEqual(linear, vec3(1.0005)));
}

// Back to sRGB, its chroma eased down in six halvings until it fits, so its lightness and hue hold: `Oklch.toRgba`.
vec3 srgbFromOklch(vec3 oklch) {
    vec3 linear = linearFromOklch(oklch);
    if (fitsSrgb(linear)) return srgbEncoded(linear);
    float fits = 0.0;
    float overflows = oklch.y;
    for (int halving = 0; halving < 6; halving++) {
        float between = (fits + overflows) * 0.5;
        if (fitsSrgb(linearFromOklch(vec3(oklch.x, between, oklch.z)))) fits = between; else overflows = between;
    }
    return srgbEncoded(linearFromOklch(vec3(oklch.x, fits, oklch.z)));
}

// `amount` of the way from one OKLCH colour to another, straight across OKLab: `Oklch.towards`.
vec3 oklchTowards(vec3 from, vec3 to, float amount) {
    vec2 fromAb = from.y * vec2(cos(from.z * FULL_TURN_RADIANS), sin(from.z * FULL_TURN_RADIANS));
    vec2 toAb = to.y * vec2(cos(to.z * FULL_TURN_RADIANS), sin(to.z * FULL_TURN_RADIANS));
    vec2 ab = mix(fromAb, toAb, amount);
    return vec3(mix(from.x, to.x, amount), length(ab), fract(atan(ab.y, ab.x) / FULL_TURN_RADIANS));
}

// `colour` turned `turn` of the way round, and a pale one drawn up to `tinge` toward a pastel of the turn's hue:
// `HueCycle.at`, whose constants these are (vivid at 0.15 chroma, pastel at 0.12, held under 0.9 lightness, red at
// 0.081 of a turn).
vec3 cycled(vec3 colour, float turn, float tinge) {
    vec3 own = oklchOf(colour);
    vec3 turned = vec3(own.xy, fract(own.z + turn));
    float drawn = tinge * (1.0 - min(own.y / 0.15, 1.0));
    if (drawn <= 0.0) return srgbFromOklch(turned);
    vec3 pastel = vec3(min(own.x, 0.9), 0.12, 0.081 + turn);
    return srgbFromOklch(oklchTowards(turned, pastel, drawn));
}
