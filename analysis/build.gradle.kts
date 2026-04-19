plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
}

application {
    mainClass.set("fi.vesas.jdbcprof.analysis.AnalyzeCli")
}
