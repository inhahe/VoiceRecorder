plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lithic.voicerecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lithic.voicerecorder"
        minSdk = 26
        targetSdk = 35
        // Single source of truth for the version. Bump on every change (see CLAUDE.md):
        // versionName is the semver shown to users and used for the GitHub release tag,
        // versionCode must strictly increase or Android refuses the update.
        versionCode = 19
        versionName = "0.13.3"
    }

    // F-Droid: no Google-encrypted dependency list in the APK signing block (F-Droid's
    // scanner rejects it, and it cannot be verified). App bundles keep theirs.
    dependenciesInfo {
        includeInApk = false
    }

    buildTypes {
        release {
            // No VCS metadata file in the APK: it would differ between our build and
            // F-Droid's, which matters if this app ever ships as a reproducible build.
            vcsInfo.include = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    testImplementation("junit:junit:4.13.2")
}
