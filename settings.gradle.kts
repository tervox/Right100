pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            setUrl("https://www.jitpack.io")
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven { setUrl("https://artifactory-external.vkpartner.ru/artifactory/maven") }
        mavenLocal()
    }
}

rootProject.name = "Gallery"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")

