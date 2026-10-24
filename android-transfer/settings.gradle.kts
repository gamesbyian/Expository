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
    }
}
rootProject.name = "ExpositoryTransfer"
include(":core:planner", ":core:engine", ":adapter:ftps", ":testing:fake-endpoints")
// Only opt in to Android/SDK resolution during explicit APK work.
// Pure planner verification needs only a JVM.
if (providers.gradleProperty("withAndroid").orNull == "true") include(":app")
