import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Releases are signed with your own key when NIHIL_KEYSTORE points to it (the
// release workflow does this, see RELEASING.md). Without it, the debug key:
// enough for your own phone.
val releaseKeystore = System.getenv("NIHIL_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.nihilshell.launcher"
    compileSdk = 35
    // Pinned so a read-only SDK (Nix) doesn't need another build-tools version.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.nihilshell.launcher"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("NIHIL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NIHIL_KEY_ALIAS")
                keyPassword = System.getenv("NIHIL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // No dependency metadata block in the APK: there is nothing to declare.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Tests only (the pure Kotlin core). The app itself has no libraries.
    testImplementation("junit:junit:4.13.2")
}
