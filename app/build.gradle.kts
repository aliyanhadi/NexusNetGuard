plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nexus.netguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexus.netguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Point this at your deployed backend-php/api/v1/config.php (HTTPS only).
        // If unreachable, the app silently falls back to built-in defaults.
        buildConfigField("String", "CONFIG_URL", "\"https://example.com/api/v1/config.php\"")
        buildConfigField("String", "TELEMETRY_URL", "\"https://example.com/api/v1/telemetry.php\"")
        // Must match NEXUS_API_TOKEN on the server if you set one there (leave empty otherwise).
        buildConfigField("String", "API_TOKEN", "\"\"")
    }

    // Release signing comes from env vars (set by the GitHub workflow from repository secrets).
    // Without them the release APK is signed with the debug key so it still installs.
    val releaseKeystore: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // Two apps from one codebase:
    //  standard = no root, no Shizuku          (com.nexus.netguard)
    //  pro      = adds Shizuku-powered features (com.nexus.netguard.pro)
    flavorDimensions += "tier"
    productFlavors {
        create("standard") { dimension = "tier" }
        create("pro") {
            dimension = "tier"
            applicationIdSuffix = ".pro"
            versionNameSuffix = "-pro"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Pro flavor only
    "proImplementation"("dev.rikka.shizuku:api:13.1.5")
    "proImplementation"("dev.rikka.shizuku:provider:13.1.5")
}
