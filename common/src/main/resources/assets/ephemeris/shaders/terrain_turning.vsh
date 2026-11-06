#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla's `core/terrain.vsh` as it stands in 26.3, with one change: a vertex's colour — where a biome tinted it, the
// grass's green, a leaf's, the water's blue — is turned round the colour wheel before the light is laid on it
// (`TurningTerrain`). An untinted vertex is a grey, and a grey has no hue to turn, so it is passed straight through.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif
#include <ephemeris:oklch.glsl>

// x: how far round the wheel the ground's colours have turned, y: the water's.
layout(std140) uniform TurningInfo {
    vec4 Turning;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;

// How far a vertex's channels may differ and it still count as grey, which the light alone makes it.
const float GREY_WITHIN = 0.004;

vec4 turnedColor() {
    float spread = max(Color.r, max(Color.g, Color.b)) - min(Color.r, min(Color.g, Color.b));
    if (spread <= GREY_WITHIN) return Color;
    #ifdef TURNS_WATER
    float turn = Turning.y;
    #else
    float turn = Turning.x;
    #endif
    return vec4(cycled(Color.rgb, turn, 0.0), Color.a);
}

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = turnedColor() * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = turnedColor();
    #endif
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
}
