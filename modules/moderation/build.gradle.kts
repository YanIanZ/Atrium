plugins {
    id("atrium.module")
}

description = "Moderation: bans, IP bans, kicks, mutes (incl. shadow), warnings with escalation, jails, freeze, history."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
