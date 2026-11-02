rootProject.name = "Atrium"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroupByRegex("io\\.papermc.*|dev\\.folia.*|net\\.md-5|com\\.mojang") }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MilkBowl")
                includeGroup("com.github.decentsoftware-eu")
                includeGroupAndSubgroups("com.github.GriefPrevention")
                includeGroupAndSubgroups("com.github.angeschossen")
                includeGroup("com.github.NEZNAMY")
                // Price engines for the worth module (EconomyShopGUI, ShopGUIPlus).
                includeGroup("com.github.Gypopo")
                includeGroup("com.github.brcdev-minecraft")
            }
        }
        maven("https://repo.fancyinnovations.com/releases") {
            content { includeGroupAndSubgroups("de.oliver"); includeGroupAndSubgroups("com.fancyinnovations") }
        }
        maven("https://maven.citizensnpcs.co/repo") {
            content { includeGroupAndSubgroups("net.citizensnpcs") }
        }
        maven("https://maven.enginehub.org/repo/") {
            content { includeGroupAndSubgroups("com.sk89q"); includeGroupAndSubgroups("com.fastasyncworldedit") }
        }
        maven("https://repo.glaremasters.me/repository/towny/") {
            content { includeGroupAndSubgroups("com.palmergames") }
        }
        maven("https://repo.opencollab.dev/main/") {
            content { includeGroupAndSubgroups("org.geysermc") }
        }
        maven("https://repo.extendedclip.com/releases/") {
            content { includeGroup("me.clip") }
        }
        maven("https://repo.bluecolored.de/releases") {
            content { includeGroupAndSubgroups("de.bluecolored") }
        }
        maven("https://repo.codemc.io/repository/maven-releases/") {
            content { includeGroupAndSubgroups("com.github.retrooper") }
        }
        // VaultUnlockedAPI (the Vault v2 API, LGPL-3.0): bundled by atrium-vault, compile-only for modules/economy.
        maven("https://repo.codemc.io/repository/creatorfromhell/") {
            content { includeModule("net.milkbowl.vault", "VaultUnlockedAPI") }
        }
        maven("https://repo.mikeprimm.com/") {
            content { includeGroupByRegex("us\\.dynmap.*") }
        }
        // Multiverse-Core 5 API (compile-only, modules/worlds).
        maven("https://repo.onarandombox.com/multiverse-releases") {
            content { includeGroupAndSubgroups("org.mvplugins") }
        }
        // InfernalSuite Advanced Slime Paper API and loaders (compile-only, modules/worlds).
        maven("https://repo.infernalsuite.com/repository/maven-releases/") {
            content { includeGroupAndSubgroups("com.infernalsuite") }
        }
    }
}

include("atrium-api", "atrium-core", "atrium-plugin")
include("atrium-vault")
// Official SourbyCraft 26.2 API sources, compile-only baseline for modules/sourbycraft (see its README.md).
include(":compat:sourbyapi-26.2")

// Every directory under modules/ that has a build.gradle.kts is a feature module (:modules:<name>).
file("modules").listFiles { f -> f.isDirectory && f.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":modules:${it.name}") }
