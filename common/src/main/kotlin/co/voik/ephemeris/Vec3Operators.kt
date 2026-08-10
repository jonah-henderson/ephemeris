package co.voik.ephemeris

import net.minecraft.world.phys.Vec3

/**
 * Arithmetic operators for Minecraft's immutable [Vec3], so vector maths reads as arithmetic —
 * `a + b * scale` rather than `a.add(b.scale(scale))`.
 *
 * **The library's own copy on purpose.** Six one-line operators are not worth a dependency in either
 * direction, and a library that borrowed its arithmetic from the mod it was extracted from would not be
 * extractable at all. A consumer with its own set keeps it; the two never meet, imports being explicit.
 */
operator fun Vec3.plus(other: Vec3): Vec3 = add(other)

operator fun Vec3.minus(other: Vec3): Vec3 = subtract(other)

operator fun Vec3.times(scalar: Double): Vec3 = scale(scalar)

operator fun Vec3.times(scalar: Float): Vec3 = scale(scalar.toDouble())

operator fun Vec3.div(scalar: Double): Vec3 = scale(1.0 / scalar)

operator fun Vec3.unaryMinus(): Vec3 = reverse()
