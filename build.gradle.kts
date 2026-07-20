plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
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
