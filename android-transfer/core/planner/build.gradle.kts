plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
}
kotlin { jvmToolchain(17) }
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Run deterministic offline G1 acceptance scenarios"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.ScenarioSuite")
}

tasks.register<JavaExec>("directoryScenarios") {
    group = "verification"
    description = "Remote create-directory immutable plan and collision checks"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.DirectoryCreateSuite")
}

tasks.register<JavaExec>("moveScenarios") {
    group = "verification"
    description = "Same-server Move planner prevents cross-server destructive routes"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SftpMoveSuite")
}
