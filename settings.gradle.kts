plugins {
    // Lets Gradle download the Java 11 toolchain the build asks for, instead of
    // failing on a machine that happens not to have one installed. Without it,
    // building this library requires a JDK 11 on the developer's box even
    // though the source compiles under anything newer.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "logduck"
