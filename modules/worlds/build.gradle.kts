plugins {
    id("atrium.module")
}

description = "World teleport across world managers (Bukkit, SourbyCraft AWF, Multiverse-Core, Advanced Slime Paper): /wtp, /worlds, /worldinfo."

dependencies {
    // Optional at runtime. The hook classes are loaded only when the plugin/API is present; nothing is bundled.
    // Multiverse-Core 5 (Paper only). The jar carries its relocated vavr types used by the API.
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.8.2") { isTransitive = false }
    // Advanced Slime Paper: the API ships inside the ASP server; the mysql/mongodb/api loaders ship with the
    // ASPaperPlugin (visible through join-classpath). Transitive so the loaders' signatures compile.
    compileOnly("com.infernalsuite.asp:api:4.1.0")
    compileOnly("com.infernalsuite.asp:mysql-loader:4.1.0")
    compileOnly("com.infernalsuite.asp:mongo-loader:4.1.0")
    compileOnly("com.infernalsuite.asp:api-loader:4.1.0")
}
