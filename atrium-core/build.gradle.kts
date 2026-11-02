plugins {
    id("atrium.java-conventions")
}

description = "Atrium framework: scheduling, commands, config, messages, storage, users, modules."

dependencies {
    api(project(":atrium-api"))
    // Downloaded at runtime by AtriumLoader (see LIBRARIES in AtriumLoader); keep both lists in sync.
    compileOnly(libs.hikari)
    compileOnly(libs.caffeine)
    compileOnly(libs.configurate.yaml)
    testImplementation(libs.hikari)
    testImplementation(libs.caffeine)
    testImplementation(libs.configurate.yaml)
    testImplementation(libs.sqlite)
}
