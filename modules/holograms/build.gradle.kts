plugins {
    id("atrium.module")
}

description = "Holograms: selectable native/DecentHolograms/FancyHolograms engine with pages, placeholders and click actions (/holo)."

dependencies {
    // Optional at runtime: every call is guarded by a check that the plugin is enabled.
    compileOnly(libs.placeholderapi) { isTransitive = false }
    // Hologram engines. Hook classes are only loaded when the matching plugin is enabled.
    compileOnly("com.github.decentsoftware-eu:decentholograms:2.8.17") { isTransitive = false }
    compileOnly("de.oliver:FancyHolograms:2.10.0") { isTransitive = false }
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
