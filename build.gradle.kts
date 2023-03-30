plugins {
    kotlin("jvm") version "1.8.10"
    kotlin("plugin.serialization") version "1.8.10"

    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "me.pfouto"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.github.docker-java:docker-java-core:3.3.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.0")
    implementation("org.slf4j:slf4j-simple:1.7.32")
    implementation("com.charleskorn.kaml:kaml:0.53.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0-Beta")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("../../deploy/${project.name}")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
}

