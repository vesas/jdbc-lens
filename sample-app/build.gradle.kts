// Bundled example application used to try the profiler end-to-end.
// Talks to an in-memory H2 database and runs a handful of plain JDBC
// queries so a recording has something interesting in it.

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.h2)
}

application {
    mainClass.set("io.github.vesas.jdbcprof.sample.Main")
}
