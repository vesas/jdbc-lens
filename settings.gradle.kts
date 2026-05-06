rootProject.name = "jdbc-prof"

include("core", "analysis", "benchmarks", "sample-app", "benchmarks-comparison")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
