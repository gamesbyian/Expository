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
