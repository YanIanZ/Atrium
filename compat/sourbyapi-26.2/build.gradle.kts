// Compile-only stand-in for the OFFICIAL SourbyCraft API (GitHub YanIanZ/SourbyCraft, branch 26.2).
//
// The API sources are GPL-3.0-only and belong to the SourbyCraft project, so they are NOT part of this repository.
// `fetchSourbyApi` downloads exactly the files listed in sources.lock from the pinned commit, verifies each SHA-256
// and compiles them against paper-api only. modules/sourbycraft compiles its baseline code against this jar, so
// `./gradlew build` fails as soon as Atrium uses an API member the official branch does not have. The result is
// compileOnly in one module and never bundled into any Atrium jar.
//
// Offline builds: pass -PsourbycraftRepo=/path/to/SourbyCraft (a clone that contains the pinned commit); the files
// are then read with `git show` instead of being downloaded. Both paths verify the same hashes.
import java.net.URI
import java.security.MessageDigest

plugins {
    id("atrium.java-conventions")
}

description = "SourbyCraft 26.2 API (official GitHub branch), compile-only baseline for modules/sourbycraft."

val lockFile = layout.projectDirectory.file("sources.lock")
val generatedDir = layout.buildDirectory.dir("generated/sourbyapi")
val sourbycraftRepo = providers.gradleProperty("sourbycraftRepo")

abstract class FetchSourbyApi : DefaultTask() {
    @get:InputFile abstract val lock: RegularFileProperty
    @get:Input @get:Optional abstract val localRepo: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val lines = lock.get().asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val commit = lines.first { it.startsWith("commit ") }.removePrefix("commit ").trim()
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "sources.lock: invalid commit $commit" }
        val entries = lines.filter { !it.startsWith("commit ") }.map {
            val (hash, path) = it.split(Regex("\\s+"), limit = 2)
            require(hash.matches(Regex("[0-9a-f]{64}")) && path.endsWith(".java") && !path.contains("..")) {
                "sources.lock: invalid entry $it"
            }
            hash to path
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for ((hash, path) in entries) {
            val bytes = if (localRepo.isPresent) {
                val process = ProcessBuilder("git", "-C", localRepo.get(), "show",
                    "$commit:sourbyapi/src/main/java/$path").redirectErrorStream(false).start()
                val data = process.inputStream.readBytes()
                check(process.waitFor() == 0) { "git show failed for $path at $commit in ${localRepo.get()}" }
                data
            } else {
                val url = URI("https://raw.githubusercontent.com/YanIanZ/SourbyCraft/$commit/sourbyapi/src/main/java/$path").toURL()
                val connection = url.openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
                connection.getInputStream().use { it.readBytes() }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == hash) { "SHA-256 mismatch for $path: expected $hash, got $actual" }
            val target = out.resolve(path)
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
        logger.lifecycle("Fetched ${entries.size} SourbyCraft API sources at $commit")
    }
}

val fetchSourbyApi = tasks.register<FetchSourbyApi>("fetchSourbyApi") {
    lock = lockFile
    localRepo = sourbycraftRepo
    outputDir = generatedDir
}

sourceSets.main {
    java {
        setSrcDirs(listOf(fetchSourbyApi.flatMap { it.outputDir }))
        // Needs SourbyCraft's patched paper-api (MapPalette.colors is package-private in upstream paper-api).
        // Server-side SIMD helper; Atrium never touches dev.iyanz.sourbycraft.api.simd.
        exclude("dev/iyanz/sourbycraft/api/simd/VectorMapPalette.java")
    }
}

tasks.withType<JavaCompile>().configureEach {
    // simd/* uses the incubating Vector API, as SourbyCraft's own build does. Third-party code: no lint noise.
    options.compilerArgs = listOf("--add-modules", "jdk.incubator.vector", "-Xlint:none", "-nowarn")
}

// Third-party sources: never publish a sources jar or javadoc for them.
tasks.matching { it.name == "sourcesJar" || it.name == "javadocJar" || it.name == "javadoc" }.configureEach { enabled = false }
