plugins {
    id("atrium.module")
}

description = "Teleports: /tp, /tpa, /back, /top, /spawn with warmup, cooldown and safe landing."

dependencies {
    // Storage round-trip tests (cooldown persistence) run against an in-memory-file SQLite database.
    testImplementation(libs.hikari)
    testImplementation(libs.sqlite)
}
