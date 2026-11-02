plugins {
    id("atrium.module")
}

description = "Random teleport: async, border-correct safe search with a ready-location pool."

dependencies {
    // Storage round-trip tests (persisted cooldowns) run against a temporary SQLite database.
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)
}
