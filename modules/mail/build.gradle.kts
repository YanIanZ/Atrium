plugins {
    id("atrium.module")
}

description = "Offline mail: send, read, clear, broadcast and expiring messages."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
