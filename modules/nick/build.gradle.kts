plugins {
    id("atrium.module")
}

description = "Nicknames with validation, permissions and /realname lookup"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
