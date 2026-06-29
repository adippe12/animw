import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        // AGP 9.x requires Gradle 9.x — keep these in sync with gradle-wrapper.properties.
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
}

// Helper accessors for the cloudstream + android extensions.
// NOTE: these are top-level `fun Project.xxx` because the cloudstream gradle
// plugin registers the `cloudstream` configuration only AFTER apply(plugin = ...)
// runs inside subprojects{}. Defining them at top-level makes Kotlin's scope
// resolution pick them up correctly inside the subprojects{} block below.
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply(configuration)
}

subprojects {
    apply(plugin = "com.android.library")
    // NOTE: do NOT apply `kotlin-android` separately — the cloudstream gradle
    // plugin applies it transitively in modern versions.
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY contains the current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/doGior/doGiorsHadEnough")
        authors = listOf("doGior", "DieGon")
    }

    android {
        namespace = "it.dogior.hadEnough"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        lint {
            targetSdk = 36
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        // Bind the configuration names locally so Kotlin's name resolution finds
        // them inside this block — required because we have top-level helpers
        // named `cloudstream` and the plugin-registered dependency configuration
        // would otherwise be shadowed.
        val implementation by configurations
        val cloudstream by configurations

        // Stubs for all Cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.1")
        // 2.16.0 — matches the upstream recloudstream/extensions repo.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
