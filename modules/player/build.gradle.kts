plugins {
    id("atrium.module")
}

description = "Player utilities: fly, heal, feed, gamemode, speed, god, ..."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
