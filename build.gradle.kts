plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
    `maven-publish`
}

group = "com.github.Nyora-Manga"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    // Publish a sources jar too so consumers (nyora-android) get readable engine code.
    withSourcesJar()
}

// Consumed as a library by nyora-android (the data-driven runtime — 35 generic
// engines + models + EngineRegistry), and buildable on JitPack as
// com.github.Nyora-Manga:nyora-data-driven:<tag>. The `application` block below is
// only the local verification harness and is not part of the published artifact.
publishing {
    publications {
        create<MavenPublication>("engine") {
            from(components["java"])
            artifactId = "nyora-data-driven"
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

kotlin {
    jvmToolchain(17)
}

// The pre-existing data-driven engine sources live in ./engine (package app.nyora.data.engine)
// with a flat layout that does not mirror the package path. The materialized model, runtime
// context and live runner live under ./src/main/kotlin with the conventional layout. Point the
// main source set at BOTH so everything compiles as one module.
sourceSets {
    main {
        kotlin.srcDirs("engine", "src/main/kotlin")
    }
}

application {
    mainClass.set("app.nyora.data.runtime.RunnerKt")
}

// Whole-catalog verification harness (every bundled engine, live).
tasks.register<JavaExec>("verifyAll") {
    group = "verification"
    description = "Run getPopular->getDetails->getPageList against a live source for every engine."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.nyora.data.runtime.VerifyAllKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        // Engines were authored against a slightly different contract; keep warnings non-fatal.
        allWarningsAsErrors.set(false)
        // Several engines use `break`/`continue` inside inline-lambda loops (Kotlin 2.x feature).
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}
