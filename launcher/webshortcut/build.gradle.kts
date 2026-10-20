import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.zane.pretttv.sevenmovies"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.zane.pretttv.sevenmovies"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Reuse the launcher project's local signing configuration; never bundle its credentials.
    val signingProps = Properties().apply {
        val f = rootProject.file("signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val keystorePath = signingProps.getProperty("storeFile") ?: System.getenv("RIVI_KEYSTORE")
    val keystorePassword = signingProps.getProperty("storePassword") ?: System.getenv("RIVI_KEYSTORE_PASSWORD")
    val keystoreAlias = signingProps.getProperty("keyAlias") ?: System.getenv("RIVI_KEY_ALIAS") ?: "rivi"
    val hasReleaseKey = keystorePath != null && keystorePassword != null && file(keystorePath).exists()

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = file(keystorePath!!)
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = true }
}
