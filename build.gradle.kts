import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.logduck"

// Single source of truth for the version. The release workflow checks the git
// tag against it and refuses to publish when they disagree.
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // `api` rather than `implementation`: both types appear in the public
    // surface — suspend functions in the client, JsonElement in event data —
    // so a consumer needs them on its own compile classpath.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    // 11 rather than anything newer: this is meant to work from an Android app
    // as well as a server, and Android build tooling is the constraint.
    jvmToolchain(11)

    // The public API is a compatibility promise. Explicit API mode makes adding
    // to it deliberate — every public declaration needs an explicit visibility
    // and return type.
    //
    // Set through the extension rather than as a raw -Xexplicit-api compiler
    // arg: the extension applies it to production sources only, whereas the
    // flag also hits the test source set, where it is pure noise.
    explicitApi()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    // Maven Central rejects unsigned artifacts. In CI the key arrives as
    // ORG_GRADLE_PROJECT_signingInMemoryKey, which Gradle surfaces as a
    // project property; locally there usually is none, and signing is skipped
    // so `./gradlew build` works without a GPG setup.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "logduck", version.toString())

    pom {
        name.set("LogDuck")
        description.set("Official Kotlin and Java SDK for LogDuck event logging and notifications")
        url.set("https://logduck.com")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("logduck")
                name.set("LogDuck")
                url.set("https://logduck.com")
            }
        }
        scm {
            url.set("https://github.com/Log-Duck/logduck-kotlin")
            connection.set("scm:git:git://github.com/Log-Duck/logduck-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/Log-Duck/logduck-kotlin.git")
        }
    }
}
