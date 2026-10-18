pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://repo.fastmcmirror.org/content/repositories/releases/")
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "ZHIColor"
include(":app")
include(":bridge-core", ":libxposed-api-stubs")
include(":lyricon-central")
