plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    jmh(project(":core"))
}

jmh {
    // Warmup, measurement, and fork counts live on @Warmup, @Measurement,
    // and @Fork annotations on each benchmark class so settings travel
    // with the code. Overriding them here would silently clobber the
    // per-benchmark discipline. Leave this block empty unless you need a
    // project-wide override that every benchmark shares.
}
