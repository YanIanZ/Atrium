plugins {
    id("atrium.module")
}

description = "Saved inventory snapshots: capture, preview, load and remove inventory, armour, offhand and ender chest."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
