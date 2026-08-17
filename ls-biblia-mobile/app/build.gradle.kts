plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.saulocosta.lsbiblia"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.saulocosta.lsbiblia"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.transformer)
    testImplementation(libs.junit)
}
