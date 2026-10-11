import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A do-nothing leanback app used only by tests/ui_test.sh to exercise install / uninstall /
// favourite handling without touching any real streaming app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "fi.jommi.rivi.testapp"
    compileSdk = 34
    defaultConfig { applicationId = "fi.jommi.rivi.testapp"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1" }
    buildTypes { debug { } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { checkReleaseBuilds = false }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
