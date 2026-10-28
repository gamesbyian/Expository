plugins { id("org.jetbrains.kotlin.jvm") version "2.2.20" }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core:planner")) }
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Deterministic streaming, mismatch and fault-injection cases"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.engine.CopyEngineSuite")
}


tasks.register<JavaExec>("journalScenarios") {
    group = "verification"
    description = "Fail-closed durable journal crash and corruption scenarios"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.engine.DurableJournalSuite")
}

tasks.register<JavaExec>("journaledCopyScenarios") {
    group = "verification"
    description = "Commit-before-mutation streaming and lost-ack tests"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.engine.JournaledCopySuite")
}

tasks.register<JavaExec>("renameScenarios") {
    group = "verification"
    description = "Fail-closed per-step bulk rename journals, cycles and interrupted operation scenarios"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.engine.JournaledRenameSuite")
}

tasks.register<JavaExec>("recursiveCopyScenarios") {
    group = "verification"
    description = "Per-step journaled recursive Copy, no-replay, partial and fault-injection E1"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.engine.RecursiveFolderCopyEngineSuite")
}
