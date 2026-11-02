plugins {
    id("atrium.module")
}

description = "Staff mode, sudo, command spy and spectator mode for administrators."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
