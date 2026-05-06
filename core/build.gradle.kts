// Core library: capture, sink, binary log format.
// No runtime dependencies beyond the JDK — keep this module lean.

plugins {
    `maven-publish`
    signing
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation(libs.h2)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "jdbc-prof-core"
            pom {
                name.set("jdbc-prof-core")
                description.set("JDBC call-site profiler — core capture and log-writing library.")
                url.set("https://github.com/vesas/jdbc-prof")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("vesas")
                        name.set("Vesa Saarinen")
                        email.set("saarive@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/vesas/jdbc-prof.git")
                    developerConnection.set("scm:git:ssh://git@github.com/vesas/jdbc-prof.git")
                    url.set("https://github.com/vesas/jdbc-prof")
                }
            }
        }
    }
    repositories {
        // Central Portal (publisher.sonatype.com) — set credentials via
        // ~/.gradle/gradle.properties or CI secrets:
        //   sonatypeUsername=<token-user>
        //   sonatypePassword=<token-password>
        // Uncomment and fill in once a Central Portal namespace is registered.
        //
        // maven {
        //     name = "centralPortalStaging"
        //     url = uri("https://central.sonatype.com/api/v1/publisher/upload")
        //     credentials {
        //         username = providers.gradleProperty("sonatypeUsername").orNull
        //         password = providers.gradleProperty("sonatypePassword").orNull
        //     }
        // }
    }
}

// Sign all publication artifacts. Keys are supplied via Gradle properties so
// that local development works without a keyring and CI injects them as secrets:
//   signing.keyId         — last 8 hex digits of the key
//   signing.secretKey     — armored private key block (-----BEGIN PGP PRIVATE KEY BLOCK-----)
//   signing.password      — passphrase
//
// Export the armored key with: gpg --armor --export-secret-keys <keyId>
signing {
    useInMemoryPgpKeys(
        providers.gradleProperty("signing.keyId").orNull,
        providers.gradleProperty("signing.secretKey").orNull,
        providers.gradleProperty("signing.password").orNull,
    )
    sign(publishing.publications["maven"])
}
