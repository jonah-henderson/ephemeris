import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
    alias(libs.plugins.powerAssert)
}

/** `check` and `require` become diagrammed assertions, in the tests only — as in the mod's `common`. */
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf("kotlin.check", "kotlin.require")
    includedSourceSets = listOf("test")
}

neoForge {
    // Vanilla only, through NeoForm — the same bargain the mod's `common` takes. Nothing in this module may
    // name a loader type, which is the whole of what lets one source tree serve both.
    neoFormVersion = libs.versions.neoForm.get()
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
}

configurations {
    create("commonJava") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonKotlin") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonResources") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

dependencies {
    // Annotations only, to compile the Mixins in `src/main/java`. Each loader supplies the implementation at
    // runtime, so this must never reach a runtime classpath.
    compileOnly(libs.mixin)
}

artifacts {
    add("commonJava", sourceSets.main.get().java.sourceDirectories.singleFile)
    add("commonKotlin", sourceSets.main.get().kotlin.sourceDirectories.filter { it.name == "kotlin" }.singleFile)
    add("commonResources", sourceSets.main.get().resources.sourceDirectories.singleFile)
}

/**
 * The library's own checks — `./gradlew :ephemeris:common:test`.
 *
 * Separate from the mod's suite because the library is separable: a check that only runs when Ages and the
 * Art builds is a check that does not travel with what it guards.
 *
 * Kotest brings its own JUnit Platform engine and its own version of it, so there is deliberately no
 * `junit-bom` — pinning both from one BOM makes them disagree.
 */
dependencies {
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
}

val main: SourceSet = sourceSets.main.get()
val test: SourceSet = sourceSets.test.get()
// Minecraft arrives compile-only under ModDevGradle, so it has to be forced onto the runtime side too.
test.compileClasspath += main.compileClasspath + main.output
test.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
