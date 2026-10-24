plugins { id("org.jetbrains.kotlin.jvm") version "2.2.20" }
kotlin { jvmToolchain(17) }
dependencies { implementation("org.apache.sshd:sshd-sftp:2.20.0") }
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Pinned SFTP host-key and path policy against synthetic keys only"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.sftp.SftpSecuritySuite")
}

tasks.register<JavaExec>("lab") {
    group = "verification"
    description = "Ephemeral loopback SFTP handshake and host-key rotation rejection"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.sftp.SftpServerLabSuite")
}


tasks.register<JavaExec>("externalSmoke") {
    group = "verification"
    description = "MANUAL ONLY: externally verified host-key pinned SFTP read-only test"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.expository.transfer.sftp.SftpExternalSmokeSuite")
}
