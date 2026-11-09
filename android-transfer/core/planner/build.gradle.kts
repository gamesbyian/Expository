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

tasks.register<JavaExec>("smartMergePreviewScenarios") {
    group = "verification"
    description = "Pure M2 N-source merge preview, collision and incomplete-inventory guards"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SmartMergePreviewPlannerSuite")
}

tasks.register<JavaExec>("smartMergeIdentityScenarios") {
    group = "verification"
    description = "Reject malformed Unicode in inert Smart Merge source identity and grant witnesses"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SmartMergeSourceIdentitySuite")
}

tasks.register<JavaExec>("smartMergeNamesScenarios") {
    group = "verification"
    description = "Pure source-label-only Smart Merge folder name suggestions and missing-range refusal"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SmartMergeNameSuggestionsSuite")
}

tasks.register<JavaExec>("smartMergeInventoryIntegrityScenarios") {
    group = "verification"
    description = "Refuse duplicate or detached source entries in complete M2 inventory pages"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.planner.SmartMergeInventoryIntegritySuite")
}

// These main()-based suites are not automatically discovered by Gradle's test task.
// Keep the standard check lifecycle honest for local developers and CI.
tasks.named("check") {
    dependsOn("scenarios", "locationScenarios", "recursiveFolderScenarios", "directoryScenarios", "moveScenarios", "workspaceScenarios", "sessionAccessScenarios", "smartMergeBasketScenarios", "smartMergePreviewScenarios", "smartMergeIdentityScenarios", "smartMergeNamesScenarios",
        "smartMergeInventoryIntegrityScenarios")
}
