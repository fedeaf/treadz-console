// Real Android BLE transport and foreground service. Implements what :treadmill defines.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.treadz.console.treadmill.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":treadmill"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
