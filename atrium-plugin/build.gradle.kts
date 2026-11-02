plugins {
    id("atrium.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Assembles the Atrium plugin jar (API + core + every feature module)."

val bundled: Configuration by configurations.creating

dependencies {
    bundled(project(":atrium-api"))
    bundled(project(":atrium-core"))
    rootProject.subprojects
        .filter { it.path.startsWith(":modules:") }
        .forEach { bundled(project(it.path)) }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.shadowJar {
    configurations = listOf(bundled)
    archiveBaseName = "Atrium"
    archiveClassifier = ""
    // Modules are discovered with ServiceLoader; every module jar ships its own service file.
    // Shadow 9 drops duplicate paths before transformers run unless duplicates are included.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Only our own projects go into the jar; third-party libraries are resolved by AtriumLoader.
    dependencies {
        include { it.moduleGroup == "dev.yanianz" }
    }
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
