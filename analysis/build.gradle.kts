plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
}

application {
    mainClass.set("fi.vesas.jdbcprof.analysis.Cli")
}

// Run relative paths against the repo root, not the subproject dir.
// Without this, `gradlew :analysis:run --args="analyze sample-app/foo"`
// invoked from the repo root fails with NoSuchFileException because
// Gradle defaults the task's working dir to `analysis/`.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
