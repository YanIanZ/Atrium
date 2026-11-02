plugins {
    id("atrium.module")
}

description = "CMI, EssentialsX, vanilla ban list and Vault economy importer, plus a SQLite backup helper"

dependencies {
    compileOnly(libs.hikari)
    compileOnly(libs.sqlite)
    compileOnly(libs.mariadb)
    // Only VaultBalanceSources touches it, and only after checking that Vault is installed.
    compileOnly(libs.vault) { isTransitive = false }
    testImplementation(libs.sqlite)
    testImplementation(libs.mariadb)
    testImplementation(libs.vault) { isTransitive = false }
    testRuntimeOnly(libs.hikari)
}
