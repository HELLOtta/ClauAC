import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

group = "io.github.hellotta"
version = "0.1.0-SNAPSHOT"
description = "Predictive (simulation-based) anticheat for Paper, built on PacketEvents."

val targetMinecraftVersion = libs.versions.minecraft.get()

// - Bundled third-party packages are moved under this package, keeping their original name as the suffix -
// - Named so it cannot be confused with ShadowJar's own relocationPrefix property inside relocateBundled -
val bundledLibrariesPackage = "io.github.hellotta.clauac.libs"

fun ShadowJar.relocateBundled(pattern: String) =
    relocate(pattern, "$bundledLibrariesPackage.$pattern")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    // - PacketEvents is published on CodeMC only; resolve its group from nowhere else -
    exclusiveContent {
        forRepository {
            maven("https://repo.codemc.io/repository/maven-releases/") {
                name = "codemc-releases"
            }
        }
        filter {
            includeGroup("com.github.retrooper")
        }
    }
}

dependencies {
    compileOnly(libs.paper.api)

    implementation(libs.packetevents.spigot) {
        // - Netty is part of the server's network stack, which PacketEvents injects into; it must never be bundled -
        exclude(group = "io.netty")
        // - Annotation-only artifact that is never needed at runtime -
        exclude(group = "org.jetbrains", module = "annotations")
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.compilerArgs.add("-Xlint:all")
    }

    processResources {
        val properties = mapOf(
            "version" to project.version.toString(),
            "description" to project.description.orEmpty(),
            "apiVersion" to targetMinecraftVersion,
        )
        inputs.properties(properties)
        filesMatching("plugin.yml") {
            expand(properties)
        }
    }

    jar {
        // - The thin jar lacks PacketEvents; mark it so the shaded jar is the unqualified artifact -
        archiveClassifier = "plain"
    }

    shadowJar {
        archiveClassifier = ""

        relocateBundled("com.github.retrooper.packetevents")
        relocateBundled("io.github.retrooper.packetevents")
        // - PacketEvents is compiled against adventure 4, while Paper ships the binary-incompatible adventure 5 -
        // - (e.g. ClickEvent.Action is no longer an enum and TranslatableComponent.args() is gone), so the whole -
        // - adventure copy PacketEvents depends on is bundled and isolated from the server's -
        relocateBundled("net.kyori")
        // - com.google.gson is deliberately not relocated even though the PacketEvents bundling guide lists it: -
        // - PacketEvents references Gson without shipping it, so those references have to reach the server's Gson -

        // - Module descriptors of bundled libraries describe packages that no longer exist after relocation -
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
    }

    runServer {
        minecraftVersion(targetMinecraftVersion)
    }
}
