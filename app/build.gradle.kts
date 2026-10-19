import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Release signing comes from a properties file kept outside the repository (storeFile, storePassword, keyAlias,
// keyPassword). Its path is the TREADZ_SIGNING_PROPERTIES environment variable or, by default,
// ~/.config/treadz-console/release-signing.properties. Without it the release build is left unsigned and the debug
// build uses the standard debug key. With it, debug builds are signed with the same key, so a debug APK and a release
// APK install over each other.
val signingProperties: Properties? = (
    System.getenv("TREADZ_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/treadz-console/release-signing.properties"
    ).let(::file).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.treadz.console"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.treadz.console"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.2"
    }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME goes into the exported TCX files.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the app resources and manifest to render the screens.
            isIncludeAndroidResources = true
            all {
                // Robolectric on JDK 17+ needs access to JRE internals.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":treadmill-android"))
    implementation(project(":hrm-android"))
    implementation(project(":programs"))
    implementation(project(":userdata"))
    implementation(project(":sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.material3.windowsize)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
