plugins {
    id("atrium.module")
}

description = "Vanish: hide staff from players, silent containers, no pickup/targeting/triggers."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
