plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "one.nullstring.ComTrak"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.nullstring.ComTrak"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val releaseKeystore = file("${rootDir}/release.keystore")
        val releaseStorePassword = providers.gradleProperty("COMTRAK_RELEASE_STORE_PASSWORD").orNull
            ?: providers.environmentVariable("COMTRAK_RELEASE_STORE_PASSWORD").orNull
        val releaseKeyPassword = providers.gradleProperty("COMTRAK_RELEASE_KEY_PASSWORD").orNull
            ?: providers.environmentVariable("COMTRAK_RELEASE_KEY_PASSWORD").orNull
        val releaseKeyAlias = providers.gradleProperty("COMTRAK_RELEASE_KEY_ALIAS").orNull
            ?: providers.environmentVariable("COMTRAK_RELEASE_KEY_ALIAS").orNull
            ?: "comtrak"

        if (releaseKeystore.exists() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Room SQLite
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Coil Image Loading
    implementation(libs.coil.compose)

    // WorkManager Background Sync
    implementation(libs.androidx.work.runtime.ktx)

    // Google Sign-In & Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit & E2E Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
}
