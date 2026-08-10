plugins {
    id("multiloader-loader")
    alias(libs.plugins.loom)
}

val modId = project.property("modId") as String

dependencies {
    minecraft(libs.minecraft)
    // No mappings: Java Edition is unobfuscated from 26.1, so none are published and none are needed.
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)
    // Kotlin as a mod, because this ships as one. A library jar cannot see the stdlib Fabric Language Kotlin
    // provides, which is the whole reason Ephemeris is a mod rather than a plain nested library.
    implementation(libs.flk)
}

loom {
    val aw = project(":common").file("src/main/resources/$modId.accesswidener")
    if (aw.exists()) {
        accessWidenerPath.set(aw)
    }
}
