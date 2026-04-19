plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
}

application {
    mainClass.set("io.github.vesas.jdbcprof.analysis.AnalyzeCli")
}
