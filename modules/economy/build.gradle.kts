plugins { id("atrium.module") }

dependencies {
    compileOnly(libs.vault) { isTransitive = false }
    testImplementation(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
