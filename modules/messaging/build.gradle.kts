plugins {
    id("atrium.module")
}

description = "Messaging: action bar messages, raw tellraw, helpop and player alerts."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
