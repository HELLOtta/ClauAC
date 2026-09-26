plugins {
    // - Lets Gradle download the Java toolchain declared by the build when it is not installed locally -
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ClauAC"

// - JDK-only interfaces shared between the plugin and the isolated vanilla runtime -
include("simulation-api")
// - Code that runs inside the isolated vanilla runtime, compiled against the unobfuscated vanilla server -
include("simulation")
