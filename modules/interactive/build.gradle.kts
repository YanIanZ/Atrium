plugins {
    id("atrium.module")
}

description = "Interactive commands: run action lines when players click bound blocks, entities or tagged items (/ic)."

dependencies {
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite)
}
