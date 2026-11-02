plugins { id("atrium.module") }

dependencies {
    compileOnly(libs.vault) { isTransitive = false }
    // Vault v2 (VaultUnlocked) API for the native v2 provider; supplied at runtime by Atrium Vault or VaultUnlocked.
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20") { isTransitive = false }
    testImplementation("net.milkbowl.vault:VaultUnlockedAPI:2.20") { isTransitive = false }
    testImplementation(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
