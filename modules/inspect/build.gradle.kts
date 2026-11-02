plugins {
    id("atrium.module")
}

description = "Read-only staff diagnostics: block, entity and item info, permissions, commands, exp, lastseen lists"

dependencies {
    // Optional: offline /checkperm and group details when LuckPerms is installed.
    compileOnly("net.luckperms:api:5.5")
}
