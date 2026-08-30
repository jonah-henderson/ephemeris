// Tiling value noise, for shapes that must not look like arithmetic.
//
// **Why this exists at all.** A sum of sines is the cheap way to make something wander, and it has a
// ceiling: however many you stack and however you squash them, the result reads as a wave that has been
// stretched in places, because that is exactly what it is (Jonah, 2026-08-30, walked). Noise has no period
// to find, so an eye stops looking for one.
//
// **It tiles, and that is not a nicety.** Time has to wrap or the float driving it eventually goes coarse
// and the animation quantises; a field that did not tile would jump at the wrap, which on the fold is a
// visible snap of the geometry. Every octave doubles its own period alongside its frequency, so the whole
// stack repeats exactly at the period handed in — and the caller's job is only to hand in one the wrapped
// time divides into.

// Where the field is sampled from, so a negative coordinate never reaches the unsigned hash. Larger than
// anything any caller here multiplies up to.
const float NOISE_ORIGIN = 262144.0;

uint ephemerisHash(uint seed) {
    seed = (seed ^ 61u) ^ (seed >> 16u);
    seed *= 9u;
    seed = seed ^ (seed >> 4u);
    seed *= 0x27d4eb2du;
    seed = seed ^ (seed >> 15u);
    return seed;
}

/** One cell's value, in `0..1`, repeating every [period] cells on each axis. */
float ephemerisCell(vec2 cell, vec2 period) {
    vec2 wrapped = mod(cell, period) + NOISE_ORIGIN;
    uvec2 at = uvec2(wrapped);
    return float(ephemerisHash(at.x + ephemerisHash(at.y)) & 0xFFFFu) / 65535.0;
}

/** Smoothly interpolated value noise, in `0..1`. */
float ephemerisNoise(vec2 at, vec2 period) {
    vec2 cell = floor(at);
    vec2 into = fract(at);
    vec2 eased = into * into * (3.0 - 2.0 * into);
    float lowLeft = ephemerisCell(cell, period);
    float lowRight = ephemerisCell(cell + vec2(1.0, 0.0), period);
    float highLeft = ephemerisCell(cell + vec2(0.0, 1.0), period);
    float highRight = ephemerisCell(cell + vec2(1.0, 1.0), period);
    return mix(mix(lowLeft, lowRight, eased.x), mix(highLeft, highRight, eased.x), eased.y);
}

/**
 * Octaves of [ephemerisNoise] summed, in `0..1` — detail at several scales at once, which is what makes a
 * shape look like a thing rather than like a texture.
 *
 * The period doubles with the frequency, so the sum tiles wherever one octave does.
 */
float ephemerisFbm(vec2 at, vec2 period, int octaves) {
    float total = 0.0;
    float amount = 0.5;
    float most = 0.0;
    for (int octave = 0; octave < octaves; octave++) {
        total += amount * ephemerisNoise(at, period);
        most += amount;
        at *= 2.0;
        period *= 2.0;
        amount *= 0.5;
    }
    return total / most;
}
