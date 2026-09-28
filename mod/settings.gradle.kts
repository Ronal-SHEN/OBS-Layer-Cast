pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Applies the right Loom flavour (remap / non-remap) per Minecraft version.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Creates `versions/{mc}-{loader}` nodes, each using `build.{loader}.gradle.kts`.
        fun match(project: String, vararg loaders: String, version: String = project) {
            for (loader in loaders) version("$project-$loader", version).buildscript("build.$loader.gradle.kts")
        }

        match("26.1.2", "fabric", "neoforge")
        match("26.2", "fabric", "neoforge")
        // NeoForge 26.3 is still beta and MaFgLib has no 26.3 build yet.
        match("26.3", "fabric")
        vcsVersion = "26.2-fabric"
    }
}

rootProject.name = "layercast"
