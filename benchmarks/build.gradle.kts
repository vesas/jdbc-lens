plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    jmh(project(":core"))
}

jmh {
    // Defaults; override from the command line with -Pjmh.* properties.
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    timeOnIteration.set("2s")
    warmup.set("1s")
}
