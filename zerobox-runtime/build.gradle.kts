plugins {
    id("com.android.library")
}

android {
    namespace = "com.zerobox.runtime"
    compileSdk = 34
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":zerobox-sdk"))
    testImplementation("junit:junit:4.13.2")
}
