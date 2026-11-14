plugins {
    id("atrium.module")
}

description = "Chat formatting, private messages, staff chat, mentions and chat moderation."

dependencies {
    compileOnly(libs.placeholderapi) { isTransitive = false }
}
