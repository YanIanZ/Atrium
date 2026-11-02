plugins {
    id("atrium.module")
}

description = "Change your skin to another account's skin, cached and persisted"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
