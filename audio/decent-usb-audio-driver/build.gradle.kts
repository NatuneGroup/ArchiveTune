plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        externalNativeBuild { cmake { cppFlags("-fno-fast-math") } }
    }

    buildTypes {
        getByName("debug")
        getByName("release")
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
        }
    }

    // Native USB isochronous driver (vendored from LastWave-native's
    // audio module — ported as part of the Lastwave Audio Processing engine).
    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
