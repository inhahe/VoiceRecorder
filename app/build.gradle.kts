import java.security.MessageDigest
import java.util.Properties

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
        versionCode = 41
        versionName = "0.18.2"
    }

    // F-Droid: no Google-encrypted dependency list in the APK signing block (F-Droid's
    // scanner rejects it, and it cannot be verified). App bundles keep theirs.
    dependenciesInfo {
        includeInApk = false
    }

    // Debug builds are what this app ships (design.md, "Signing"), so they must be signed with the
    // key every earlier release was: Android installs an update only over an app signed with the
    // same key. That key is kept outside the repo, and local.properties (per machine, never
    // committed) says where: debugKeystore=<path>, with forward slashes, since a .properties file
    // eats backslashes as escapes, and relative to the project folder unless absolute. Without
    // that entry the build signs with the machine's own debug key, whose APKs cannot update an
    // install of a release; checkReleaseKey, below, catches that before a release goes out.
    signingConfigs {
        getByName("debug") {
            val local = Properties()
            rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { local.load(it) }
            local.getProperty("debugKeystore")?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

// Fails unless the debug APK is signed with the release key, the certificate whose SHA-256 this
// pins (a public fingerprint, not a secret). publish-release.bat runs it before uploading, since
// a release signed with any other key can update no existing install, and stays published.
tasks.register("checkReleaseKey") {
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    doLast {
        val expected = "667f61111417c0384afd6cddb306cbfdbedaecb98245f56c7d759d008da688f8"
        val file = apk.get().asFile
        val result = com.android.apksig.ApkVerifier.Builder(file).build().verify()
        if (!result.isVerified) throw GradleException("$file does not verify: ${result.errors}")
        val signers = result.signerCertificates.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
        }
        if (signers != listOf(expected)) {
            throw GradleException(
                "$file is signed with $signers, not the release key $expected, so it could " +
                    "update no existing install: set debugKeystore= in local.properties to the " +
                    "release keystore (design.md, \"Signing\")."
            )
        }
        logger.lifecycle("Signed with the release key.")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // The player (ExoOutput). 1.9 is the newest that builds against compileSdk 35.
    implementation("androidx.media3:media3-exoplayer:1.9.4")

    testImplementation("junit:junit:4.13.2")
}
