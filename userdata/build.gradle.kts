// Local data layer: user profiles, weight tracking and per-user workout history (Room).
// Logic is tested on the PC with Robolectric: `./gradlew :userdata:testDebugUnitTest`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.treadz.console.userdata"
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
            }
        }
    }

    sourceSets {
        // The exported Room schemas are test assets for the migration test helper.
        getByName("test").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    // Room schema files, one per database version. Commit them: migrations are validated against them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":treadmill"))
    // Program, plan and fitness-test models and their JSON documents (pure Kotlin, like :treadmill).
    api(project(":programs"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
