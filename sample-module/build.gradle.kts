plugins {
    id("com.android.library")
}

android {
    namespace = "com.zerobox.sample"
    compileSdk = 34
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    compileOnly(project(":zerobox-sdk"))
    compileOnly(project(":zerobox-xposed-bridge"))
    testImplementation(project(":zerobox-sdk"))
    testImplementation(project(":zerobox-xposed-bridge"))
    testImplementation("junit:junit:4.13.2")
}
