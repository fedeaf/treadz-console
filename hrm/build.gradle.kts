// Pure Kotlin (no Android): heart-rate strap support (standard Heart Rate service 0x180D), independent of the
// treadmill: contract, decoding, connection logic over an abstract transport, a simulated strap and the resting
// heart-rate measurement. Tested on the PC with `./gradlew :hrm:test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":analysis"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
