import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fi.jommi.rivi"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.zane.pretttv"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.1.9"
        testInstrumentationRunner = "fi.jommi.rivi.StabilityInstrumentation"
    }

    // Release signing is optional. Put a `signing.properties` file next to settings.gradle.kts
    // (storeFile, storePassword, keyAlias; never committed), or set RIVI_KEYSTORE,
    // RIVI_KEYSTORE_PASSWORD and RIVI_KEY_ALIAS. Without either, the release build is
    // signed with the local debug key so that anyone can build and install it.
    val signingProps = Properties().apply {
        val f = rootProject.file("signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val keystorePath: String? = signingProps.getProperty("storeFile") ?: System.getenv("RIVI_KEYSTORE")
    val keystorePassword: String? = signingProps.getProperty("storePassword") ?: System.getenv("RIVI_KEYSTORE_PASSWORD")
    val keystoreAlias: String = signingProps.getProperty("keyAlias") ?: System.getenv("RIVI_KEY_ALIAS") ?: "rivi"
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
        create("stability") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".stability"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    testBuildType = "stability"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")   // JVM unit tests only; nothing ships in the APK
}
