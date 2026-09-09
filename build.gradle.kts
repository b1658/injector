plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.screenmate.can.injector"
    compileSdk = 34
    defaultConfig {
        applicationId = "co.screenmate.can.injector"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    // Pure loader: no CAN-TX. Patcher bind-mounts the read agent over the box's own root adbd via
    // dadb (pure-JVM ADB client over loopback) — that's the only dependency this app needs. TX lives
    // in :tx-client and its consumers, intentionally not here.
    implementation("dev.mobile:dadb:1.2.9")
}
