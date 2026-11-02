// Atrium Vault: a Folia-ready drop-in replacement for the Vault plugin.
// The unmodified VaultAPI classes (LGPL-3.0, Vault API v1) and VaultUnlockedAPI classes (LGPL-3.0, Vault API v2,
// package net.milkbowl.vault2) are shaded WITHOUT relocation so every plugin compiled against either API links
// against exactly the same classes; everything else in the jar is our own code.
plugins {
    id("atrium.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Atrium Vault: Folia-supporting, guarded drop-in replacement for the Vault plugin (Vault API v1 and v2)."
// Same major.minor as the bundled VaultUnlockedAPI, like VaultUnlocked itself (which ships as plugin "Vault" 2.20.x):
// plugins that check for a v2-capable Vault by version see 2.20, and v1 plugins compare it as newer than 1.7.
version = "2.20.0-atrium.3"

val vaultApiVersion = libs.versions.vault.get()
val vaultUnlockedApiVersion = "2.20"
val vaultUnlockedApi = "net.milkbowl.vault:VaultUnlockedAPI:$vaultUnlockedApiVersion"

// Only the VaultAPI artifact itself; its Bukkit/JUnit transitive dependencies must never be shaded.
val shade: Configuration by configurations.creating { isTransitive = false }
// VaultUnlockedAPI also contains copies of the v1 classes; only its net/milkbowl/vault2 package is taken from it.
val shadeV2: Configuration by configurations.creating { isTransitive = false }

dependencies {
    shade(libs.vault) { isTransitive = false }
    shadeV2(vaultUnlockedApi) { isTransitive = false }
    compileOnly(libs.vault) { isTransitive = false }
    compileOnly(vaultUnlockedApi) { isTransitive = false }
    testImplementation(libs.vault) { isTransitive = false }
    testImplementation(vaultUnlockedApi) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "vaultApiVersion" to vaultApiVersion,
        "vaultUnlockedApiVersion" to vaultUnlockedApiVersion,
    )
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "atrium-vault.properties")) { expand(props) }
}

tasks.shadowJar {
    configurations = listOf(shade)
    archiveBaseName = "Vault"
    archiveClassifier = ""
    // No relocation: binary compatibility with VaultAPI 1.7.x and VaultUnlockedAPI 2.x must be identical.
    dependencies {
        include { it.moduleGroup == "com.github.MilkBowl" && it.moduleName == "VaultAPI" }
    }
    from(shadeV2.elements.map { jars -> jars.map { zipTree(it) } }) {
        include("net/milkbowl/vault2/**")
    }
    exclude("META-INF/maven/org.bukkit/**")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
