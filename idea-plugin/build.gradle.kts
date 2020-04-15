group = "com.github.turansky.yfiles"
version = "0.2.1-SNAPSHOT"

plugins {
    kotlin("jvm") version "1.3.72"
    id("org.jetbrains.intellij") version "0.4.18"
}

repositories {
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

intellij {
    pluginName = "yfiles"

    version = "2020.1"

    setPlugins(
        "gradle",
        "java",
        "org.jetbrains.kotlin:1.3.72-release-IJ2020.1-1"
    )
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
            allWarningsAsErrors = true
        }
    }

    patchPluginXml {
        sinceBuild("193.5233")
        untilBuild("202.*")
    }

    publishPlugin {
        setToken(project.property("intellij.publish.token"))
    }

    wrapper {
        gradleVersion = "6.3"
        distributionType = Wrapper.DistributionType.ALL
    }
}
