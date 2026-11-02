plugins {
    id("atrium.module")
}

description = "Boss bar announcements, /bossbarmsg and /bossbar toggle."

dependencies {
    // Shared display engine (placeholders, animations, toggles) lives in the tablist module's
    // dev.yanianz.atrium.module.display package; every module ships in the same plugin jar.
    compileOnly(project(":modules:tablist"))
    testImplementation(project(":modules:tablist"))
}
