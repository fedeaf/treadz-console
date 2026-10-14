// Real Android BLE transport for heart-rate straps and the app's entry point. Implements what :hrm defines.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.treadz.console.hrm.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":hrm"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
