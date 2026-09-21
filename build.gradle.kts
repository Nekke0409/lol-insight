import org.springframework.boot.gradle.tasks.run.BootRun
import java.io.File

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.nekke0409"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.openai:openai-java:4.63.1") {
        // springdoc's Jakarta variant must be the only Swagger annotation provider at runtime.
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

fun loadDotenv(file: File): Map<String, String> {
    if (!file.isFile) {
        return emptyMap()
    }

    return file
        .readLines()
        .mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@mapIndexedNotNull null
            }

            val separator = line.indexOf('=')
            require(separator > 0) { ".env line ${index + 1} must use KEY=VALUE format." }

            val key = line.substring(0, separator).trim()
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                ".env line ${index + 1} has an invalid environment variable name."
            }

            val rawValue = line.substring(separator + 1).trim()
            val value = rawValue.removeSurrounding("\"").removeSurrounding("'")
            key to value
        }.toMap()
}

tasks.named<BootRun>("bootRun") {
    val dotenv = loadDotenv(layout.projectDirectory.file(".env").asFile)

    // Existing shell variables take precedence over local defaults and secrets in .env.
    environment(dotenv.filterKeys { System.getenv(it) == null })
}
