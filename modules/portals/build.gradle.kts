plugins {
    id("atrium.module")
}

description = "Portals: admin-defined regions that teleport players to a warp, a location or (later) another server."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
