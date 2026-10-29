import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spendring.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spendring.app"
        minSdk = 26
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + run
        versionName = "build-$run"
    }

    // A fixed signing key from the repo's hidden GitHub secrets, so every new build installs
    // over the old one and your purchases are kept. The key itself is never in the code.
    val ksFile = rootProject.file("keystore.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("fixed") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "spendring"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No third-party libraries and no internet: Android framework + Kotlin stdlib only.
dependencies {
}
