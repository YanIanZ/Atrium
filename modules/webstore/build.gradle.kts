plugins {
    id("atrium.module")
}

description = "Webstore deliveries: runs the console commands of paid or gifted store packages."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
