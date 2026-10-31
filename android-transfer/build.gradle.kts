// Fail closed if any project/adapter dependency resolves through a dynamic
// version selector or changing module. The rule covers transitive dependencies
// and both JVM-only and opt-in Android subprojects; it does not substitute for
// reviewed lockfiles, artifact checksums or a verified Gradle wrapper.
subprojects {
    configurations.configureEach {
        resolutionStrategy.failOnNonReproducibleResolution()
    }
}
