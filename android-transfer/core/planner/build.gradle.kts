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

tasks.register<JavaExec>("locationScenarios") {
    group = "verification"
    description = "Bounded provider-neutral paged listing and GitHub/Drive transfer guards"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.PagedLocationSuite")
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

tasks.register<JavaExec>("workspaceScenarios") {
    group = "verification"
    description = "Pure E1 single/dual independent workspace navigation and plan parity"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.WorkspaceStateSuite")
}

tasks.register<JavaExec>("sessionAccessScenarios") {
    group = "verification"
    description = "RAM-only Android biometric/device-credential access cadence and replay policy"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SessionAccessGateSuite")
}

tasks.register<JavaExec>("recursiveFolderScenarios") {
    group = "verification"
    description = "Pure E1 bounded nested/empty-folder copy manifest and authority guards"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.RecursiveFolderCopySuite")
}

tasks.register<JavaExec>("smartMergeBasketScenarios") {
    group = "verification"
    description = "Pure M1 multi-provider N-source merge basket guards (no writes)"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SmartMergeBasketSuite")
}

// These main()-based suites are not automatically discovered by Gradle's test task.
// Keep the standard check lifecycle honest for local developers and CI.
tasks.named("check") {
    dependsOn("scenarios", "locationScenarios", "recursiveFolderScenarios", "directoryScenarios", "moveScenarios", "workspaceScenarios", "sessionAccessScenarios", "smartMergeBasketScenarios")
}
