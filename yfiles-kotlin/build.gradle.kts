import com.github.turansky.yfiles.generateKotlinDeclarations
import de.undercouch.gradle.tasks.download.Download

group = "com.yworks.yfiles"
version = "23.0.2-SNAPSHOT"

plugins {
    kotlin("js")
    id("com.github.turansky.yfiles")

    id("de.undercouch.download")

    id("maven-publish")
}

kotlin.js {
    browser()
}

val kotlinSourceDir: File
    get() = kotlin
        .sourceSets
        .get("main")
        .kotlin
        .sourceDirectories
        .first()

tasks {
    clean {
        delete("src")
    }

    val apiDescriptorFile = File(buildDir, "api.js")
    val devguideDescriptorFile = File(buildDir, "devguide.js")

    val downloadApiDescriptor by registering(Download::class) {
        src(project.property("yfiles.api.url"))
        dest(apiDescriptorFile)
        overwrite(true)
    }

    val downloadDevguideDescriptor by registering(Download::class) {
        src(project.property("yfiles.devguide.url"))
        dest(devguideDescriptorFile)
        overwrite(true)
    }

    val generateDeclarations by registering {
        doLast {
            val sourceDir = kotlinSourceDir
                .also { delete(it) }

            generateKotlinDeclarations(
                apiFile = apiDescriptorFile,
                devguideFile = devguideDescriptorFile,
                sourceDir = sourceDir
            )
        }

        dependsOn(downloadApiDescriptor)
        dependsOn(downloadDevguideDescriptor)
    }

    named("compileKotlinJs") {
        dependsOn(generateDeclarations)
        finalizedBy(publishToMavenLocal)
    }
}

publishing {
    publications {
        register<MavenPublication>("mavenKotlin") {
            from(components["kotlin"])
            artifact(tasks.getByName("jsSourcesJar"))
        }
    }
}