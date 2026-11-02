plugins {
    id("atrium.module")
}

description = "Optional protection (WorldGuard, GriefPrevention, Lands, Towny) and LuckPerms/Vault metadata bridges (/integrations)."

dependencies {
    // Optional at runtime: every hook is isolated and created only after isPluginEnabled() returned true.
    compileOnly(libs.vault) { isTransitive = false }
    compileOnly("net.luckperms:api:5.5")

    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.15") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.2.15") { isTransitive = false }

    compileOnly("com.github.GriefPrevention:GriefPrevention:16.18.8") { isTransitive = false }
    compileOnly("com.github.angeschossen:LandsAPI:7.25.4") { isTransitive = false }
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.2.15") { isTransitive = false }
}
