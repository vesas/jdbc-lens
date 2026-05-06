// Bundled example application used to try the profiler end-to-end.
// Talks to an in-memory H2 database and runs a handful of plain JDBC
// queries so a recording has something interesting in it.

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.h2)
    // P6Spy is wired so the end-to-end benchmark in :benchmarks-comparison
    // can launch this app under -Djdbcprof.mode=p6spy. The configuration
    // (spy.properties) lives in :benchmarks-comparison and is picked up
    // from the subprocess classpath; this module brings only the P6Spy
    // classes themselves so Main can construct a P6DataSource. Day-to-
    // day use (mode=jdbcprof or mode=none) never touches P6Spy.
    implementation(libs.p6spy)
}

application {
    mainClass.set("fi.vesas.jdbclens.sample.Main")
}
