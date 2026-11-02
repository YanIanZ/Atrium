plugins {
    id("atrium.module")
}

description = "Automatic and manual AFK detection, broadcasts and idle actions"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
