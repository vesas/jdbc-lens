rootProject.name = "jdbc-lens"

include("core", "analysis", "benchmarks", "sample-app", "benchmarks-comparison")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
