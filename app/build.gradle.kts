import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.wortschatz"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.wortschatz"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.1"

        // Only 64-bit ARM phones are targeted; native libraries added later ship for arm64 alone.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Every build is signed with the same key so new versions install over old ones and keep the
    // learner's data. See signing/keystore.properties.
    val signing = Properties().apply { rootProject.file("signing/keystore.properties").reader().use { load(it) } }
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/" + signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // Bundled pronunciation is played straight from the APK, which needs it stored uncompressed.
        noCompress += "webm"
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // Anki 2.1.50+ packages are zstd-compressed.
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
}
