rootProject.name = "jdbc-prof"

include("core", "analysis", "benchmarks", "sample-app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
