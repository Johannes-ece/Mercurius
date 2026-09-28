plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.jvg.mercurius"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.jvg.mercurius"
        // Built for one Pixel 8, so no need to support anything older than Android 14.
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
    }

    // Release signing comes from ~/.gradle/gradle.properties, so no key material lives in the repo.
    val storeFilePath = providers.gradleProperty("MERCURIUS_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("MERCURIUS_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("MERCURIUS_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("MERCURIUS_STORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 keeps the APK small; proguard-rules.pro keeps what Shizuku and app_process load by name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
