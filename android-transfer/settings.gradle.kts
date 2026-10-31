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
include(":core:planner", ":core:engine", ":adapter:ftps", ":adapter:sftp", ":testing:fake-endpoints")
// Only opt in to Android/SDK resolution during explicit APK work.
// Pure planner verification needs only a JVM.
if (providers.gradleProperty("withAndroid").orNull == "true") {
    include(":app")
    // Independent OS-discoverable provider, installed only by the dedicated
    // synthetic E2 picker workflow. Never part of the production app APK.
    if (providers.gradleProperty("withPickerFixture").orNull == "true") {
        include(":testing:documents-provider-fixture")
    }
}
