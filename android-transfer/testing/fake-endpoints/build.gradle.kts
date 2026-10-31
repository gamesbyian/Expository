plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core:planner")) }
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Run synthetic local/remote/Drive endpoint adapter scenarios"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.fakes.FakeEndpointSuite")
}

// These main()-based suites are not automatically discovered by Gradle's test task.
// Keep the standard check lifecycle honest for local developers and CI.
tasks.named("check") {
    dependsOn("scenarios")
}
