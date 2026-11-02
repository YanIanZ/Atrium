plugins {
    id("atrium.module")
}

description = "Player particle cosmetics: shapes, colours, presets and a menu"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
