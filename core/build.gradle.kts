import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: scheduling, card model and word-book parsing. No Android dependency,
// so it can be unit-tested on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("com.github.luben:zstd-jni:1.5.6-9")
}

tasks.test {
    useJUnitPlatform()
}

// Lists every text the app may speak, for tools/gen_audio.py.
tasks.register<JavaExec>("audioManifest") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.wortschatz.core.AudioKt")
    args(
        rootProject.file("app/src/main/assets/wordbooks").path,
        rootProject.layout.buildDirectory.file("audio-manifest.tsv").get().asFile.path,
    )
}
