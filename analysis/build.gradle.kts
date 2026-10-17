// Pure Kotlin (no Android, no other project module): calculations on recorded data. Metabolic equations, fitness norms,
// estimate combination, heart-rate zone conversion and RR-interval processing.
// Tested on the PC with `./gradlew :analysis:test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
