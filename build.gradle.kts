plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.allopen") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-camel-bom:${quarkusPlatformVersion}"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Reactive REST and core
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-kotlin-serialization")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.quarkus:quarkus-arc")
    
    // Reactive database
    implementation("io.quarkus:quarkus-hibernate-reactive-panache-kotlin")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    
    // Reactive messaging and WebSockets
    implementation("io.quarkus:quarkus-websockets")
    // Commenting out reactive messaging for now to fix build issues
    // implementation("io.quarkus:quarkus-smallrye-reactive-messaging")
    // implementation("io.quarkus:quarkus-smallrye-reactive-messaging-kafka") // Optional: for event streaming
    
    // Scheduler and background tasks
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-quartz")
    
    // Security for dashboard protection
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-smallrye-jwt")
    implementation("io.quarkus:quarkus-smallrye-jwt-build")
    implementation("org.mindrot:jbcrypt:0.4")
    
    // External integrations
    implementation("org.apache.camel.quarkus:camel-quarkus-tika")
    // Commenting out rest client reactive for now to fix build issues
    // implementation("io.quarkus:quarkus-rest-client-reactive")
    // implementation("io.quarkus:quarkus-rest-client-reactive-kotlin-serialization")
    
    // HTTP client for external API calls - temporarily commented out due to build issues
    // implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    // implementation("io.quarkus:quarkus-rest-client-reactive")
    // implementation("io.quarkus:quarkus-rest-client-reactive-kotlin-serialization")
    
    // OpenAPI and frontend
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkiverse.quinoa:quarkus-quinoa:2.6.2")
    
    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-test-security")
}

group = "gr.accio"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        javaParameters = true
    }
}
