// Core library: capture, sink, binary log format.
// No runtime dependencies beyond the JDK — keep this module lean.

plugins {
    `maven-publish`
}

java {
    withSourcesJar()
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
            }
        }
    }
}
