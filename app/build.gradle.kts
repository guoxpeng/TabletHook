plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.roro.tablethook"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.roro.tablethook.test"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.dexkit)
    compileOnly(libs.libxposed.api)
}
