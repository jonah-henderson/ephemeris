package co.voik.ephemeris

import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sphere of the given [radius] centred on the origin, with helpers for placing things on its
 * surface. Reusable geometry for skies, celestial bodies, particle shells, and the like — it keeps
 * the trigonometry in one well-named place instead of scattered through render code.
 */
class Sphere(val radius: Double) {
    /** A uniformly-distributed random point on the surface. */
    fun randomSurfacePoint(random: Random): Vec3 = randomDirection(random) * radius

    /**
     * The four corners of a small square lying flat against the sphere at [surfacePoint] — i.e. in
     * the tangent plane there, facing the centre — with the given [halfSize], rotated by
     * [spinRadians] within that plane. Useful for billboarded points such as stars.
     */
    fun tangentQuad(surfacePoint: Vec3, halfSize: Double, spinRadians: Double): List<Vec3> {
        val outward = surfacePoint.normalize()
        val (right, up) = tangentBasis(outward)
        val spunRight = right * cos(spinRadians) + up * sin(spinRadians)
        val spunUp = up * cos(spinRadians) - right * sin(spinRadians)
        val acrossHalf = spunRight * halfSize
        val upHalf = spunUp * halfSize
        return listOf(
            surfacePoint - acrossHalf - upHalf,
            surfacePoint + acrossHalf - upHalf,
            surfacePoint + acrossHalf + upHalf,
            surfacePoint - acrossHalf + upHalf,
        )
    }

    companion object {
        private const val NEAR_VERTICAL = 0.99

        /**
         * A uniformly-distributed random unit vector: a random point in the cube `[-1, 1]^3`, kept only
         * if inside the unit sphere, then normalised. **Normalising a cube point directly is not
         * uniform** — the corners sit up to `sqrt(3)` from the centre against the faces' 1, so the result
         * clumps toward the eight diagonals. Rejecting outside the sphere removes that bias.
         */
        fun randomDirection(random: Random): Vec3 {
            while (true) {
                val candidate = Vec3(
                    random.nextDouble() * 2.0 - 1.0,
                    random.nextDouble() * 2.0 - 1.0,
                    random.nextDouble() * 2.0 - 1.0,
                )
                val lengthSquared = candidate.lengthSqr()
                if (lengthSquared > 1.0e-6 && lengthSquared <= 1.0) {
                    return candidate * (1.0 / sqrt(lengthSquared))
                }
            }
        }

        /**
         * Two orthonormal vectors that span the plane perpendicular to [normal]. Cross [normal] with
         * a reference axis to get one tangent, then cross again to get the other; the reference is
         * swapped near the poles so the two are never parallel.
         */
        private fun tangentBasis(normal: Vec3): Pair<Vec3, Vec3> {
            val reference = if (abs(normal.y) > NEAR_VERTICAL) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val right = reference.cross(normal).normalize()
            val up = normal.cross(right)
            return right to up
        }
    }
}
