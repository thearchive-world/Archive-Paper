pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "archive-paper"

for (name in listOf("archive-api", "archive-server")) {
    include(name)
    findProject(":$name")!!.projectDir = file(name)
}
