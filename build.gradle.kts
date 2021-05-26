plugins {
    id("org.jetbrains.intellij") version "0.7.3"
    java
    kotlin("jvm") version "1.5.10"
    id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
}

group = "io.dragnea"
version = "1.0-SNAPSHOT"

intellij {
    version = "IU-2021.1.1"

    setPlugins(
        "java",
        "Spring",
        "org.jetbrains.kotlin:211-1.5.10-release-891-IJ7142.45"
    )

    updateSinceUntilBuild = false
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    runIde {
        maxHeapSize = "4G"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
}
