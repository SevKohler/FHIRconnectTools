plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "org.fhirconnect"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against a locally installed IDE (no SDK download) when -PlocalIdePath=... is given,
        // otherwise against IntelliJ IDEA Community 2024.3 from the JetBrains repository.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdeaCommunity("2024.3")
        }
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "org.fhirconnect.idea"
        name = "FHIRconnect Paths"
        version = project.version.toString()
        description = """
            Shows the resolved openEHR and FHIR paths of FHIRconnect mapping methods inline while you edit,
            looked up in the project's operational templates / web templates and FHIR implementation guide.
            Adds inspections for paths and names that do not resolve, and path completion.
        """.trimIndent()
        vendor {
            name = "FHIRconnect"
            url = "https://github.com/SevKohler/FHIRconnect-spec"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    test {
        useJUnit()
        testLogging { showStandardStreams = true }
        listOf("fc.libPath", "fc.files").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}
