plugins {
    id("atrium.module")
}

description = "Custom death messages per cause, radius broadcast, death location and kill statistics."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
