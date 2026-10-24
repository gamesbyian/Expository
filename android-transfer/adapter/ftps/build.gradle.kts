plugins { id("org.jetbrains.kotlin.jvm") version "2.2.20" }
kotlin { jvmToolchain(17) }
dependencies { implementation("commons-net:commons-net:3.13.0") }
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Offline FTPS hardening cases; no network or credentials"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.ftps.FtpsSecuritySuite")
}
