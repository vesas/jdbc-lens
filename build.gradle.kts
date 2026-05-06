plugins {
    id("com.gradleup.nmcp.aggregation") version "1.5.0"
}

nmcpAggregation {
    centralPortal {
        username = providers.gradleProperty("sonatypeUsername").get()
        password = providers.gradleProperty("sonatypePassword").get()
        publishingType = "USER_MANAGED"
    }
}

dependencies {
    nmcpAggregation(project(":core"))
}

allprojects {
    group = "fi.vesas.jdbclens"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("assertj-core").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all"))
    }
}
