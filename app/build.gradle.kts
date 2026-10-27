plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.roro.tablethook"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.roro.tablethook"
        minSdk = 30
        targetSdk = 37
        versionCode = 26
        versionName = "2.4.2-manifest"
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
