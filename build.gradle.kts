import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        // IMPORTANT: don't bump past 2.0.20 — newer Kotlin versions break
        // the cloudstream gradle plugin's DSL resolution inside the
        // dependencies { cloudstream("...") } block.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
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
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // NOTE: do NOT define a top-level `fun Project.cloudstream(...)` helper here.
    // It clashes with the `cloudstream` dependency configuration that the
    // cloudstream gradle plugin registers, and Kotlin resolves
    // `dependencies { cloudstream("...") }` to the helper instead of the
    // configuration — causing "Type mismatch: inferred type is String but
    // CloudstreamExtension.() -> Unit was expected".
    //
    // Use the verbose `extensions.configure<>()` form instead.
    extensions.configure<CloudstreamExtension>("cloudstream") {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/doGior/doGiorsHadEnough")
    }

    extensions.configure<BaseExtension>("android") {
        namespace = "com.lagradost.cloudstream3.${project.name.lowercase().replace("-", "_")}"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
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
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        // DO NOT BUMP past 2.13.1 — newer versions break on Android < 8.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}
