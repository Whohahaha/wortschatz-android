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
        versionCode = 14
        versionName = "0.12.0"

        // Only 64-bit ARM phones are targeted; native libraries added later ship for arm64 alone.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Every build is signed with the same key so new versions install over old ones and keep the
    // learner's data. The key is never in the repo: CI gets it from GitHub Secrets through the
    // WORTSCHATZ_* environment variables; locally they can come from a keystore.properties file
    // (WORTSCHATZ_SIGNING_PROPERTIES, default ~/.android-signing/wortschatz/keystore.properties).
    // Without a key, builds fall back to the throwaway debug key and must not be published.
    // The expected certificate fingerprint is public: signing/cert-sha256.txt.
    val localSigning = Properties().apply {
        val path = System.getenv("WORTSCHATZ_SIGNING_PROPERTIES")
            ?: "${System.getProperty("user.home")}/.android-signing/wortschatz/keystore.properties"
        val file = File(path)
        if (file.isFile) file.reader().use { load(it) }
    }
    fun signingValue(env: String, key: String): String? =
        System.getenv(env)?.takeIf { it.isNotEmpty() } ?: localSigning.getProperty(key)
    val keystorePath = signingValue("WORTSCHATZ_KEYSTORE", "storeFile")
    val fixedSigning = keystorePath?.let { path ->
        signingConfigs.create("fixed") {
            storeFile = file(path)
            storePassword = signingValue("WORTSCHATZ_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("WORTSCHATZ_KEY_ALIAS", "keyAlias") ?: "wortschatz"
            keyPassword = signingValue("WORTSCHATZ_KEY_PASSWORD", "keyPassword")
        }
    }
    if (fixedSigning == null) {
        logger.warn("Wortschatz: no signing key found, using the debug key. Do not publish this APK.")
    }

    buildTypes {
        debug {
            if (fixedSigning != null) signingConfig = fixedSigning
        }
        release {
            isMinifyEnabled = false
            if (fixedSigning != null) signingConfig = fixedSigning
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
