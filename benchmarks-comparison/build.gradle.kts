// Head-to-head overhead measurement: runs three small JDBC workloads
// against H2 in three modes (no profiler, jdbc-prof, P6Spy) and emits
// a results.json plus an SVG bar chart for the README. This is *not*
// JMH — that lives in :benchmarks and is scoped to the 5 us hot-path
// budget (spec §11). This module measures realistic-app overhead so
// the comparison is honest about allocation, GC, and steady-state
// throughput.

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.h2)
    implementation(libs.p6spy)
    implementation(libs.picocli)
    // sample-app is launched as a subprocess by the e2e runner. It's
    // pulled in at runtime only — we never call into it from this
    // module — so it shows up on `java.class.path` and the spawned
    // process can resolve fi.vesas.jdbcprof.sample.Main.
    runtimeOnly(project(":sample-app"))
}

application {
    mainClass.set("fi.vesas.jdbcprof.comparison.Cli")
}

// Run relative paths against the repo root so default output paths
// (results.json, docs/img/overhead-vs-p6spy.svg) land where the
// README expects them.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
