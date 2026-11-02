plugins {
    id("atrium.module")
}

description = "Event effects (particles, sounds, titles, fireworks) and persistent fixed effects at locations"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
