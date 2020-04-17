plugins {
    id("org.jetbrains.intellij") version "0.4.21"
    java
    kotlin("jvm") version "1.3.72"
}

group = "io.dragnea"
version = "1.0-SNAPSHOT"

intellij {
    version = "IU-LATEST-EAP-SNAPSHOT"

    setPlugins(
            "java",
            "Spring",
            "org.jetbrains.kotlin:1.3.72-release-IJ2020.1-3"
    )

    updateSinceUntilBuild = false
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
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
