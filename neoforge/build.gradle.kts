plugins {
    id("multiloader-loader")
    alias(libs.plugins.moddev)
}

val modId = project.property("modId") as String

neoForge {
    version = libs.versions.neoforge.get()
    val at = project(":common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // Kotlin as a mod. See the note in the Fabric half — this is why Ephemeris ships as a mod and not as a
    // nested library jar, which on NeoForge could not see the standard library at all.
    implementation(libs.kff)
}
