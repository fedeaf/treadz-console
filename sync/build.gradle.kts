// Optional synchronisation of the app's data with one private GitHub repository shared by several devices:
// GitHub REST client, repository format, sync engine, encrypted token, background pushes (WorkManager).
// Logic is tested on the PC with Robolectric and a fake GitHub: `./gradlew :sync:testDebugUnitTest`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.treadz.console.sync"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric on JDK 17+ needs access to JRE internals.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                // The optional test against the real GitHub only runs with -PrealGitHub=true.
                val real = providers.gradleProperty("realGitHub").orNull == "true"
                it.systemProperty("treadz.realGitHub", real.toString())
                if (real) {
                    it.filter.includeTestsMatching("*RealGitHubTest")
                } else {
                    it.filter.excludeTestsMatching("*RealGitHubTest")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":userdata"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}
