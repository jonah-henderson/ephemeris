package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec

/**
 * That a body is hidden by lit air rather than by darkness.
 *
 * The rule everything here rests on: a daytime moon is pale because sky light is scattered *in front* of it,
 * not because it is dimmer. So haze must depend on how lit the air is and how much of it lies along the line
 * of sight, and on nothing else — and must vanish entirely at night and on an airless world.
 */
class AirinessCheck : FunSpec({

    val thick = 1.0f

    test("a body is solid at night, whatever height it is at") {
        for (height in listOf(-5.0f, 0.0f, 20.0f, 90.0f)) {
            val solidity = Airiness.solidityAt(height, skyLit = 0.0f, thickness = thick)
            check(solidity == 1.0f) {
                "At $height° under an unlit sky a body was $solidity solid. Nothing is scattering, so " +
                    "nothing should be hiding it"
            }
        }
    }

    test("an airless level never hazes, however bright its day") {
        for (height in listOf(0.0f, 45.0f, 90.0f)) {
            check(Airiness.solidityAt(height, skyLit = 1.0f, thickness = 0.0f) == 1.0f) {
                "A body at $height° on an airless world was hazed, and there is no air to haze it"
            }
        }
    }

    test("by day a low body is hazier than a high one") {
        val overhead = Airiness.solidityAt(90.0f, skyLit = 1.0f, thickness = thick)
        val halfway = Airiness.solidityAt(45.0f, skyLit = 1.0f, thickness = thick)
        val setting = Airiness.solidityAt(0.0f, skyLit = 1.0f, thickness = thick)

        check(overhead > halfway && halfway > setting) {
            "Solidity did not fall with height: overhead $overhead, halfway $halfway, setting $setting. " +
                "Looking at the horizon is looking along the air rather than through it"
        }
    }

    test("a body never disappears altogether") {
        // A moon that vanishes at dusk is worse than one that is merely pale — you would think it a bug.
        val worst = Airiness.solidityAt(0.0f, skyLit = 1.0f, thickness = 4.0f)
        check(worst > 0.1f) { "At its haziest a body was only $worst solid, which is gone rather than pale" }
    }

    test("haze comes on with the day rather than jumping") {
        val across = (0..10).map { Airiness.solidityAt(20.0f, skyLit = it / 10.0f, thickness = thick) }
        check(across == across.sortedDescending()) {
            "Solidity did not fall steadily as the sky lit: $across"
        }
        check(across.first() - across.last() > 0.2f) {
            "Between night and full day a body's solidity moved only ${across.first() - across.last()}, " +
                "which nobody would see"
        }
    }

    test("airmass is most at the horizon and least overhead") {
        check(Airiness.airmassAt(0.0f) == 1.0f) { "The horizon is the most air there is, by definition" }
        check(Airiness.airmassAt(90.0f) < 0.3f) { "Straight up should be the thinnest line through the air" }
        // Below the horizon it stops changing: a body already set is not going to get hazier.
        check(Airiness.airmassAt(-30.0f) == Airiness.airmassAt(0.0f)) {
            "A body below the horizon reported different air from one on it"
        }
    }
})
