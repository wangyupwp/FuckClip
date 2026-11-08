plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.fuckclip"
    compileSdk = 35

    val defaultVersionName = (project.findProperty("appVersionName") as? String) ?: "1.0.6"
    val defaultVersionCode = run {
        val clean = defaultVersionName.trim().removePrefix("v").removePrefix("V")
        val parts = clean.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val major = parts.getOrElse(0) { 1 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        major * 10000 + minor * 100 + patch
    }

    defaultConfig {
        applicationId = "io.github.cail_gainey.fuckclip"
        minSdk = 29
        targetSdk = 35
        versionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: defaultVersionCode
        versionName = defaultVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug builds are not shrunk by default, which leaves the whole dependency graph in the
            // dex (about 66 MB against 3.9 MB for release) and inflates resident memory well beyond
            // what users see. Pass -PminifyDebug=true to build a debuggable APK at release-like size
            // when profiling memory: ./gradlew :app:assembleDebug -PminifyDebug=true
            val minifyDebug = (project.findProperty("minifyDebug") as? String)?.toBoolean() ?: false
            isMinifyEnabled = minifyDebug
            isShrinkResources = minifyDebug
            if (minifyDebug) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hook"))

    // LibXposed
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // AndroidX & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material.kolor)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
