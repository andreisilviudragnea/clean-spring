plugins {
    id("org.jetbrains.intellij") version "0.7.2"
    java
    kotlin("jvm") version "1.4.32"
}

group = "io.dragnea"
version = "1.0-SNAPSHOT"

intellij {
    version = "IU-2020.3.3"

    setPlugins(
            "java",
            "Spring",
            "org.jetbrains.kotlin:203-1.4.32-release-IJ7148.5"
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
