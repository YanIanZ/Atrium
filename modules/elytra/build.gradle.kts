plugins {
    id("atrium.module")
}

description = "Elytra: firework-free sneak boost while gliding and an optional double-jump launch."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
