rootProject.name = "CloudstreamPlugins"

// Auto-include every subdirectory that ships its own build.gradle.kts.
// To add a new provider, just create the directory — no edit needed here.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        // Skip providers whose build.gradle.kts declares status = 0 (Down).
        val content = File(dir, "build.gradle.kts").readText()
        val isInactive = content.contains(Regex("""status\s*=\s*0"""))
        if (!isInactive) include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
