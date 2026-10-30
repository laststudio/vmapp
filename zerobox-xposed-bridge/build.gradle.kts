plugins {
    id("com.android.library")
}

android {
    namespace = "com.zerobox.xposed"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(project(":zerobox-sdk"))
    testImplementation(project(":zerobox-sdk"))
    testImplementation("junit:junit:4.13.2")
}
