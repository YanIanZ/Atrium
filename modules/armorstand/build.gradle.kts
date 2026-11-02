plugins {
    id("atrium.module")
}

description = "Armor stand editor: toggles, per-part poses, saved poses and main-hand equipment swaps."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
