// Core library: capture, sink, binary log format.
// No runtime dependencies beyond the JDK — keep this module lean.

plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp") version "0.1.2"
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
            artifactId = "jdbc-lens-core"
            pom {
                name.set("jdbc-lens-core")
                description.set("JDBC call-site profiler — core capture and log-writing library.")
                url.set("https://github.com/vesas/jdbc-lens")
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
                    connection.set("scm:git:git://github.com/vesas/jdbc-lens.git")
                    developerConnection.set("scm:git:ssh://git@github.com/vesas/jdbc-lens.git")
                    url.set("https://github.com/vesas/jdbc-lens")
                }
            }
        }
    }
    nmcp {
      publishAllPublicationsToCentralPortal {
          username = providers.gradleProperty("sonatypeUsername").orNull ?: ""
          password = providers.gradleProperty("sonatypePassword").orNull ?: ""
          publishingType = "USER_MANAGED"   // AUTOMATIC or "USER_MANAGED" to confirm in the UI
      }
  }
}

// Sign all publication artifacts. Keys are supplied via Gradle properties so
// that local development works without a keyring and CI injects them as secrets:
//   signing.keyId         — last 8 hex digits of the key
//   signing.secretKey     — armored private key block (-----BEGIN PGP PRIVATE KEY BLOCK-----)
//   signing.password      — passphrase
//
// Export the armored key with: gpg --armor --export-secret-keys <keyId>
// Signing is skipped when no key is configured (local dev); CI must supply all three.
val signingKey = providers.gradleProperty("signing.secretKey").orNull
if (!signingKey.isNullOrBlank()) {
    signing {
        useInMemoryPgpKeys(
            providers.gradleProperty("signing.keyId").orNull,
            signingKey,
            providers.gradleProperty("signing.password").orNull,
        )
        sign(publishing.publications["maven"])
    }
}
