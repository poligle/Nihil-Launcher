import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystore = System.getenv("NIHIL_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

val nihilVersion = "0.4.1"
val nihilVersionCode = 40199

fun versionCodeOf(name: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(name)
        ?: error("nihilVersion must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-rc.N, not $name")
    val (major, minor, patch, rc) = match.destructured
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (rc.toIntOrNull() ?: 99)
}

check(nihilVersionCode == versionCodeOf(nihilVersion)) {
    "nihilVersionCode is $nihilVersionCode, but nihilVersion $nihilVersion is ${versionCodeOf(nihilVersion)}"
}

android {
    namespace = "com.nihilshell.launcher"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.nihilshell.launcher"
        minSdk = 31
        targetSdk = 35
        versionCode = nihilVersionCode
        versionName = nihilVersion
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
    testImplementation("junit:junit:4.13.2")
}
