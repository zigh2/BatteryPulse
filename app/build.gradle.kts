import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The single source of truth for the version is the VERSION file at the repo root.
val appVersionName: String = rootProject.file("VERSION")
    .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "0.01" }
val appVersionCode: Int = (providers.gradleProperty("appVersionCode").orNull ?: "1").toInt()

base.archivesName.set("BatteryPulse-v$appVersionName")

android {
    namespace = "com.batterypulse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.batterypulse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("main") {
            // Override with env vars / GitHub secrets for your own key.
            storeFile = System.getenv("BP_KEYSTORE")?.let { file(it) } ?: file("batterypulse.keystore")
            storePassword = System.getenv("BP_STORE_PASSWORD") ?: "batterypulse"
            keyAlias = System.getenv("BP_KEY_ALIAS") ?: "batterypulse"
            keyPassword = System.getenv("BP_KEY_PASSWORD") ?: "batterypulse"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("main")
        }
        debug {
            signingConfig = signingConfigs.getByName("main")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
