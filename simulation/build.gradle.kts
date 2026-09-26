import io.github.hellotta.clauac.gradle.ExtractVanillaServer

plugins {
    id("clauac.java-conventions")
}

val extractVanillaServer = tasks.register<ExtractVanillaServer>("extractVanillaServer") {
    versionJsonUrl = providers.gradleProperty("minecraftVersionJsonUrl")
    versionJsonSha1 = providers.gradleProperty("minecraftVersionJsonSha1")
    outputDirectory = layout.buildDirectory.dir("vanilla")
}
val vanillaServer = files(extractVanillaServer.flatMap { it.outputDirectory })

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":simulation-api"))
    // - The vanilla server and its libraries are provided at runtime by the isolated class loader, never bundled -
    compileOnly(vanillaServer.asFileTree.matching { include("server/**/*.jar", "libraries/**/*.jar") })
    // - The class files of the vanilla server and of its Guava reference these annotations; the server does not -
    // - ship them because they are only needed while compiling -
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.error.prone.annotations)
    compileOnly(libs.j2objc.annotations)
}

tasks.processResources {
    // - Tells the plugin which bundler to load at runtime; it must be the one this module was compiled against -
    from(vanillaServer.asFileTree.matching { include("vanilla.properties") }) {
        into("META-INF/clauac")
    }
}
