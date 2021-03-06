plugins {
    `java-gradle-plugin`
    `kotlin-dsl`

    id("com.gradle.plugin-publish") version "0.12.0"
    id("com.github.turansky.kfc.plugin-publish") version "1.0.0"

    kotlin("jvm") version "1.4.10"
}

repositories {
    jcenter()
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}

dependencies {
    compileOnly(kotlin("gradle-plugin"))
    compileOnly(kotlin("compiler-embeddable"))
}

pluginPublish {
    gradlePluginPrefix = true
}

gradlePlugin {
    plugins {
        create("yfiles") {
            id = "com.github.turansky.yfiles"
            implementationClass = "com.github.turansky.yfiles.gradle.plugin.YFilesGradleSubplugin"
        }
    }
}

val REPO_URL = "https://github.com/turansky/yfiles-kotlin"

pluginBundle {
    website = REPO_URL
    vcsUrl = REPO_URL

    plugins.getByName("yfiles") {
        displayName = "yFiles Kotlin/JS plugin"
        description = "yFiles class framework helper for Kotlin/JS"
        tags = listOf(
            "yfiles",
            "kotlin",
            "kotlin-js",
            "javascript"
        )
        version = project.version.toString()
    }
}

// TODO: remove after Gradle update on Kotlin 1.4
tasks.compileKotlin {
    kotlinOptions.allWarningsAsErrors = false
}

tasks.wrapper {
    gradleVersion = "6.7"
    distributionType = Wrapper.DistributionType.ALL
}
