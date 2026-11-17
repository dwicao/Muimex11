plugins {
    alias(libs.plugins.android.application)
}

// Release signing is provided by GitHub Actions as environment variables.
// When they are unset (e.g. building locally), the release APK stays unsigned.
val releaseKeystorePath: String? = System.getenv("MUIMEX_KEYSTORE")

android {
    namespace = "com.muimex11.official"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.muimex11.official"
        minSdk = 24
        targetSdk = 37
        versionCode = 66
        versionName = "4.36"
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("MUIMEX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MUIMEX_KEY_ALIAS")
                keyPassword = System.getenv("MUIMEX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Tracked, not ignored: CI runs lint on every push and fails on new
        // errors. Warnings stay visible in the uploaded report.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}