// Atrium Vault: a Folia-ready drop-in replacement for the Vault plugin.
// The unmodified VaultAPI classes (LGPL-3.0) are shaded WITHOUT relocation so every plugin compiled
// against Vault links against exactly the same classes; everything else in the jar is our own code.
plugins {
    id("atrium.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Atrium Vault: Folia-supporting, guarded drop-in replacement for the Vault plugin."
// Vault's version format: plugins that parse the leading "1.7" keep working.
version = "1.7.3-atrium.2"

val vaultApiVersion = libs.versions.vault.get()

// Only the VaultAPI artifact itself; its Bukkit/JUnit transitive dependencies must never be shaded.
val shade: Configuration by configurations.creating { isTransitive = false }

dependencies {
    shade(libs.vault) { isTransitive = false }
    compileOnly(libs.vault) { isTransitive = false }
    testImplementation(libs.vault) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "vaultApiVersion" to vaultApiVersion)
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "atrium-vault.properties")) { expand(props) }
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName = "Vault"
    archiveClassifier = ""
    // No relocation: binary compatibility with VaultAPI 1.7.x must be identical.
    dependencies {
        include { it.moduleGroup == "com.github.MilkBowl" && it.moduleName == "VaultAPI" }
    }
    exclude("META-INF/maven/org.bukkit/**")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
