plugins {
    id("atrium.module")
}

description = "Seen, whois and the grouped player list."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
