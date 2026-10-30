plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zerobox.app"
    compileSdk = 34
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "com.zerobox.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-cleanroom"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":zerobox-sdk"))
    implementation(project(":zerobox-runtime"))
    implementation(project(":zerobox-xposed-bridge"))
    implementation(project(":sample-module"))
    implementation("androidx.core:core-ktx:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
