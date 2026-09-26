// - Compiler settings shared by every ClauAC module -
plugins {
    java
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.compilerArgs.add("-Xlint:all")
}
