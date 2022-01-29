plugins {
    kotlin("jvm") version "1.6.10"
    id("org.jetbrains.intellij") version "1.3.1"
    id("org.jlleitschuh.gradle.ktlint") version "10.2.1"
}

group = "io.dragnea"
version = "1.0-SNAPSHOT"

intellij {
    version.set("IU-2021.3.2")

    plugins.set(
        listOf(
            "java",
            "Spring",
            "org.jetbrains.kotlin:213-1.6.10-release-923-IJ5744.223"
        )
    )

    updateSinceUntilBuild.set(false)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "11"
            allWarningsAsErrors = true
        }
    }
    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "11"
            allWarningsAsErrors = true
        }
    }
    runIde {
        maxHeapSize = "4G"
    }
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
