plugins {
    id("atrium.module")
}

description = "Custom text pages (/ctext): admin-written MiniMessage texts with pages, aliases, join messages and reload."

dependencies {
    // Optional at runtime: every call is guarded by a check that the plugin is enabled.
    compileOnly(libs.placeholderapi) { isTransitive = false }
}
