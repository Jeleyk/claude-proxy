import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "org.claudeproxy"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.0"
val exposedVersion = "0.58.0"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")

    // Ktor client (upstream forwarding)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Persistence
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.50.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.3.0")

    // Security
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Serialization / util
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("org.claudeproxy.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// Build the React SPA into src/main/resources/static (served by Ktor).
val buildFrontend = tasks.register<Exec>("buildFrontend") {
    workingDir = file("frontend")
    commandLine("sh", "-c", "pnpm install && pnpm build")
}
tasks.named("processResources") { mustRunAfter(buildFrontend) }

// Fat jar for deployment
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "org.claudeproxy.ApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Drop signature files from signed dependencies, else the JVM rejects the fat jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

// One command to produce a self-contained fat jar with the UI baked in:
//   ./gradlew bundle   ->  build/libs/claude-proxy-<version>-all.jar
tasks.named<Jar>("fatJar") { mustRunAfter(buildFrontend) }
tasks.register("bundle") {
    group = "build"
    description = "Build the frontend and package a self-contained fat jar."
    dependsOn(buildFrontend, "fatJar")
}
