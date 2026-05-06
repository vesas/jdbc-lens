// Core library: capture, sink, binary log format.
// No runtime dependencies beyond the JDK — keep this module lean.

plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
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
}

// nmcp marks this module's publications for aggregation at the root project level.
// Credentials and publishingType are configured there via nmcpAggregation {}.

// Signing uses the system gpg command. Configure in ~/.gradle/gradle.properties:
//   signing.gnupg.keyName=ABCD1234   (last 8 hex digits of your key)
//   signing.gnupg.passphrase=...
// Signing is skipped when keyName is not set (local dev without a keyring).
if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
    signing {
        useGpgCmd()
        sign(publishing.publications["maven"])
    }
}
