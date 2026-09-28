plugins {
    // Applies the correct Loom variant for the Minecraft version (non-remap Loom on 26.1+).
    id("dev.kikugie.loom-back-compat")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    strictMaven("https://maven.terraformersmc.com/releases", "TerraformersMC", "com.terraformersmc")
}

dependencies {
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi(
        "fabric-api-base",
        "fabric-lifecycle-events-v1",
        "fabric-rendering-v1",
        "fabric-command-api-v2",
        "fabric-resource-loader-v0",
    )

    modImplementation("maven.modrinth:malilib:${property("deps.malilib")}")
    modCompileOnly("com.terraformersmc:modmenu:${property("deps.modmenu")}") { isTransitive = false }
    modLocalRuntime("com.terraformersmc:modmenu:${property("deps.modmenu")}") { isTransitive = false }
}

// End-to-end tests driven by Fabric's client game test framework: `./gradlew :26.2-fabric:runClientGameTest`.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "layercast-test"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

dependencies {
    "gametestImplementation"(fabricApi.module("fabric-client-gametest-api-v1", sc.properties["deps.fabric_api"]))
}

// Third-party mods for compatibility tests, as Modrinth `slug:versionId` pairs (their Modrinth dependencies are not
// resolved; the full Fabric API is added):
// ./gradlew :26.2-fabric:runClientGameTest -Playercast.testMods=minihud:K5zZmb6o,appleskin:uo5bAN1Y
val testMods = (findProperty("layercast.testMods") as String?).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
if (testMods.isNotEmpty()) {
    dependencies {
        modLocalRuntime("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
        testMods.forEach { modLocalRuntime("maven.modrinth:$it") }
    }
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1")
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run/fabric")
        // FFM (java.lang.foreign) is used for GPU texture sharing.
        vmArg("--enable-native-access=ALL-UNNAMED")
        // Pass-through switches for manual/automated testing, e.g. -Playercast.forceAll=true
        listOf("layercast.forceAll", "layercast.splitAll", "layercast.transport", "layercast.test.holdSeconds", "layercast.test.out", "layercast.test.benchmark", "layercast.test.seconds", "layercast.test.f3", "layercast.test.matrix", "layercast.test.resize", "layercast.test.width", "layercast.test.height", "layercast.profile", "layercast.test.fpsLimit", "mixin.debug.export").forEach { key ->
            (project.findProperty(key) as String?)?.let { vmArg("-D$key=$it") }
        }
        // -Playercast.jfr=<file> records a Java Flight Recorder profile of the game.
        (project.findProperty("layercast.jfr") as String?)?.let { vmArg("-XX:StartFlightRecording=filename=$it,settings=profile") }
        // -Playercast.graphicsBackend=vulkan|opengl forces Minecraft's render backend.
        (project.findProperty("layercast.graphicsBackend") as String?)?.let { programArgs("--graphicsBackend", it) }
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, value: String) {
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", sc.properties["mod.id"])
            register("name", sc.properties["mod.name"])
            register("version", sc.properties["mod.version"])
            register("minecraft", sc.properties["mod.mc_compat"])
            register("loader", Regex("\\d+\\.\\d+").find(sc.properties.get<String>("deps.fabric_loader"))!!.value)
        }
        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        val clientMixins = LayerCastMixins.json(LayerCastMixins.client(
            fabric = true,
            hudClass = sc.current.parsed >= "26.2",
            vulkan = sc.current.parsed >= "26.2",
            featureSets = sc.current.parsed >= "26.3",
            nameTags = sc.current.parsed < "26.3",
        ))
        inputs.property("client_mixins", clientMixins)
        filesMatching("*.mixins.json") { expand("java" to mixinJava, "client_mixins" to clientMixins) }
        exclude("META-INF/neoforge.mods.toml")

    }

    withType<Jar> {
        from(rootProject.file("../LICENSE")) { rename { "${it}_layercast" } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"
        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
