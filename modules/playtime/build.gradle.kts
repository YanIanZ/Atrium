plugins {
    id("atrium.module")
}

description = "Active playtime, leaderboards and playtime rewards"

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
