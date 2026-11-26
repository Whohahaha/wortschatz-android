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
    val localSigningFile = File(
        System.getenv("WORTSCHATZ_SIGNING_PROPERTIES")
            ?: "${System.getProperty("user.home")}/.android-signing/wortschatz/keystore.properties"
    )
    val localSigning = Properties().apply {
        if (localSigningFile.isFile) localSigningFile.reader().use { load(it) }
    }
    fun signingValue(env: String, key: String): String? =
        System.getenv(env)?.takeIf { it.isNotEmpty() } ?: localSigning.getProperty(key)
    // A relative storeFile in the properties file is resolved next to that file.
    val keystoreFile = System.getenv("WORTSCHATZ_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let { File(it) }
        ?: localSigning.getProperty("storeFile")?.let { File(it) }
            ?.let { if (it.isAbsolute) it else File(localSigningFile.parentFile, it.path) }
    val fixedSigning = keystoreFile?.let { keystore ->
        signingConfigs.create("fixed") {
            storeFile = keystore
            storePassword = signingValue("WORTSCHATZ_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("WORTSCHATZ_KEY_ALIAS", "keyAlias") ?: "wortschatz"
            keyPassword = signingValue("WORTSCHATZ_KEY_PASSWORD", "keyPassword")
        }
    }
    if (fixedSigning == null) {
        logger.warn("Wortschatz: no signing key found; debug builds use the throwaway debug key (do not publish them).")
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

// A release APK without the fixed key would be unsigned and could not be installed.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (android.signingConfigs.findByName("fixed") == null) {
            throw GradleException(
                "No Wortschatz signing key: set WORTSCHATZ_KEYSTORE/_STORE_PASSWORD/_KEY_PASSWORD " +
                    "or create ~/.android-signing/wortschatz/keystore.properties."
            )
        }
    }
}
