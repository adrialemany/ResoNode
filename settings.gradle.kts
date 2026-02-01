pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack es necesario si usas librerías externas de Github en el futuro
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SpotiFly"
include(":app")