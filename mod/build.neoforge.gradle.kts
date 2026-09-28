plugins {
    id("net.neoforged.moddev") version "2.0.147"
    id("neoforge-mutex")
}

version = "${property("mod.version")}+${sc.properties.get<String>("mod.mc_label")}"
base.archivesName = "${property("mod.id") as String}+neoforge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
}

dependencies {
    implementation("maven.modrinth:mafglib:${property("deps.mafglib")}")
    runtimeOnly("maven.modrinth:foxifiedclasstweaker:${property("deps.foxified_classtweaker")}")
}

// Third-party mods for compatibility tests, as Modrinth `slug:versionId` pairs (their dependencies are not resolved):
// ./gradlew :26.2-neoforge:runClientTest -Playercast.testMods=jade:GBES6etT,appleskin:slnk1Qah
val testMods = (findProperty("layercast.testMods") as String?).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
dependencies {
    testMods.forEach { runtimeOnly("maven.modrinth:$it") }
}

// Dev-only automation mod (NeoForge has no client game tests): `./gradlew :26.2-neoforge:runClientTest`.
val devtest: SourceSet = sourceSets.create("devtest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[devtest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[devtest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

neoForge {
    version = property("deps.neo_loader") as String

    mods {
        register(property("mod.id") as String) {
            sourceSet(sourceSets.main.get())
        }
        register("layercast_devtest") {
            sourceSet(devtest)
        }
    }

    runs {
        fun net.neoforged.moddevgradle.dsl.RunModel.common() {
            gameDirectory = file("../../run/neoforge")
            client()
            jvmArguments.add("--enable-native-access=ALL-UNNAMED")
            listOf("layercast.forceAll", "layercast.splitAll", "layercast.transport", "layercast.test.holdSeconds", "layercast.test.out").forEach { key ->
                (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
            }
            (project.findProperty("layercast.graphicsBackend") as String?)?.let { programArguments.addAll("--graphicsBackend", it) }
        }
        register("client") {
            common()
            loadedMods = setOf(mods.getByName(property("mod.id") as String))
        }
        register("clientTest") {
            common()
            sourceSet = devtest
            systemProperty("layercast.test.autorun", "true")
        }
    }
}

neoForge.addModdingDependenciesTo(devtest)

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
        }
        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        val clientMixins = LayerCastMixins.json(LayerCastMixins.client(
            fabric = false,
            hudClass = sc.current.parsed >= "26.2",
            vulkan = sc.current.parsed >= "26.2",
            featureSets = sc.current.parsed >= "26.3",
            nameTags = sc.current.parsed < "26.3",
        ))
        inputs.property("client_mixins", clientMixins)
        filesMatching("*.mixins.json") { expand("java" to mixinJava, "client_mixins" to clientMixins) }
        exclude("fabric.mod.json")

    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    withType<Jar> {
        from(rootProject.file("../LICENSE")) { rename { "${it}_layercast" } }
        // layercast+<loader>+<mod version>+<minecraft versions>.jar ("+" instead of Gradle's "-" before the version)
        archiveFileName = archiveBaseName.zip(archiveVersion) { name, version -> "$name+$version" }
            .zip(archiveClassifier.orElse("")) { name, classifier -> if (classifier.isEmpty()) "$name.jar" else "$name-$classifier.jar" }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"
        inputs.property("version", project.property("mod.version"))
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
