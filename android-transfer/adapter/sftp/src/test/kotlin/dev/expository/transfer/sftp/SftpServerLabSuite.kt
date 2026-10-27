package dev.expository.transfer.sftp

import dev.expository.transfer.planner.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Ephemeral loopback-only OpenSSH-compatible SFTP server, synthetic fixture.
 * No external services, production keys, passwords, files or user traffic.
 */
object SftpServerLabSuite {
    @JvmStatic fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("expository-sftp-fixture-")
        val payload = "Synthetic SFTP fixture\n"
        Files.writeString(tmp.resolve("synthetic.txt"), payload, StandardCharsets.UTF_8)
        // 205 owned children exercise the real adapter beyond its former cap.
        val many = Files.createDirectory(tmp.resolve("many"))
        repeat(205) { index ->
            Files.writeString(many.resolve("entry-" + index.toString().padStart(4, '0') + ".txt"),
                "synthetic child $index", StandardCharsets.UTF_8)
        }

        // Fully generated in-memory host key. Avoid any on-disk persisted host
        // identity or silently trusting first-use keys in client code.
        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        val hostKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        sshd.keyPairProvider = KeyPairProvider.wrap(hostKey)
        sshd.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator {
            user, password, _ -> user == "synthetic" && password == "synthetic-password"
        }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        sshd.fileSystemFactory = VirtualFileSystemFactory(tmp)
        try {
            sshd.start()
            val port = sshd.port
            val actualHostKey = hostKey.public
            val correct = SftpHostKeyPin(SftpHostKeyPin.observed(actualHostKey))
            val wrong = SftpHostKeyPin(SftpHostKeyPin.observed(
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            ))
            val trusted = SecureSftp.connect(SftpTarget("127.0.0.1", port, correct),
                "synthetic", "synthetic-password".toCharArray())
            trusted.use { session ->
                check("synthetic.txt" in session.list("/"))
                val browse = session.browse("/")
                check(!browse.truncated)
                check(browse.entries.any { it.name == "synthetic.txt" && !it.directory })
                val firstWindow = session.browse("/many")
                check(firstWindow.truncated && firstWindow.entries.size == 200) {
                    "First bounded window must not pretend 205 rows are complete"
                }
                val nextWindow = session.browse("/many", maxEntries = 400)
                check(!nextWindow.truncated && nextWindow.entries.size == 205)
                // Legacy list may not silently return a truncated sample:
                // the existing 205-item fixture must be fully represented.
                check(session.list("/many").size == 205)
                check(session.list("/many").toSet() ==
                    nextWindow.entries.map { it.name }.toSet())
                check(nextWindow.entries.map { it.name }.toSet().size == 205)
                check(nextWindow.entries.any { it.name == "entry-0204.txt" })
                check(session.openRead("/many/entry-0204.txt").use { it.readBytes() }
                    .contentEquals("synthetic child 204".toByteArray(StandardCharsets.UTF_8)))
                check(runCatching { session.browse("/many", maxEntries = 2001) }.isFailure)
                check(runCatching {
                    session.openExclusiveWrite("/many/entry-0204.txt").use {
                        it.write(byteArrayOf(77))
                    }
                }.isFailure) { "Expanded browsing cannot weaken no-overwrite rule" }
                check(session.openRead("/many/entry-0204.txt").use { it.readBytes() }
                    .contentEquals("synthetic child 204".toByteArray(StandardCharsets.UTF_8)))
                println("PASS SFTP LAB 205 remote items, bounded 200/400 windows, no overwrite")

                val mutationWindow = session.completeMutationListing("/many")
                check(mutationWindow.entries.size == 205 && !mutationWindow.truncated)
                check(mutationWindow.entries.map { it.name }.toSet().size == 205)
                check(runCatching {
                    SftpMutationListing.requireComplete(mutationWindow.copy(truncated = true))
                }.isFailure)
                val mutationCap = CapabilityProof(
                    Support.SUPPORTED, "verified-synthetic-205-mutation", Long.MAX_VALUE)
                val mutationParent = LocationRef(
                    LocationKey(LocationKind.REMOTE_SFTP, "isolated-session",
                        "synthetic-root", "/many"),
                    listOf("many"), "full-205-child-witness",
                    mapOf(Capability.WRITE to mutationCap,
                        Capability.CREATE_CHILD to mutationCap),
                    verifiedNamespace = "isolated-session",
                    verifiedStorageDomain = "synthetic-server"
                )
                val largeFolderPlan = DirectoryCreatePlanner.plan(
                    DirectoryCreateRequest(mutationParent, "new-after-205",
                        DestinationListing("full-205-child-witness", true,
                            mutationWindow.entries.map { it.name })), 100L)
                check(largeFolderPlan.executable)
                session.createPlannedDirectory(largeFolderPlan)
                check(session.observeDirectory("/many/new-after-205"))
                check(session.completeMutationListing("/many").entries.any {
                    it.name == "new-after-205" && it.directory
                })
                check(runCatching { session.createPlannedDirectory(largeFolderPlan) }.isFailure)
                println("PASS SFTP LAB complete 205-entry mutation witness + guarded MKDIR")

                val observed = session.statFile("/synthetic.txt")
                check(observed.size == payload.toByteArray(StandardCharsets.UTF_8).size.toLong())
                check(runCatching { session.statFile("/") }.isFailure)
                val received = session.openRead("/synthetic.txt").use { it.readBytes() }
                check(String(received, StandardCharsets.UTF_8) == payload)
                val uploadPayload = ("Synthetic upload bytes" + 10.toChar()).toByteArray(StandardCharsets.UTF_8)
                session.openExclusiveWrite("/synthetic-upload.txt").use {
                    it.write(uploadPayload)
                }
                check(session.statFile("/synthetic-upload.txt").size == uploadPayload.size.toLong())
                val readBack = session.openRead("/synthetic-upload.txt").use { it.readBytes() }
                check(readBack.contentEquals(uploadPayload))
                check(runCatching {
                    session.openExclusiveWrite("/synthetic.txt").use { it.write(byteArrayOf(0)) }
                }.isFailure) { "Exclusive write must never overwrite existing file" }
                check(session.openRead("/synthetic.txt").use { it.readBytes() }
                    .contentEquals(payload.toByteArray(StandardCharsets.UTF_8)))
                println("PASS SFTP LAB exclusive new upload/read-back and existing-file refusal")
                val capability = CapabilityProof(Support.SUPPORTED, "isolated-live-mkdir", Long.MAX_VALUE)
                val root = LocationRef(
                    LocationKey(LocationKind.REMOTE_SFTP, "isolated-session", "synthetic-root", "/"),
                    emptyList(), "known-live-listing",
                    mapOf(Capability.WRITE to capability, Capability.CREATE_CHILD to capability),
                    verifiedNamespace = "isolated-session", verifiedStorageDomain = "synthetic-server"
                )
                val beforeCreate = session.browse("/")
                val plan = DirectoryCreatePlanner.plan(DirectoryCreateRequest(root, "new-dir",
                    DestinationListing("observed-live-listing", !beforeCreate.truncated,
                        beforeCreate.entries.map { it.name })), 100L)
                check(plan.executable)
                session.createPlannedDirectory(plan)
                check(session.observeDirectory("/new-dir"))
                check(session.browse("/").entries.any { it.name == "new-dir" && it.directory })
                check(runCatching { session.createPlannedDirectory(plan) }.isFailure) {
                    "A repeated MKDIR must not silently mutate an existing directory"
                }
                println("PASS SFTP LAB planned direct MKDIR, observed folder, duplicate refused")
                val sourceBytes = ("Move payload" + 10.toChar()).toByteArray(StandardCharsets.UTF_8)
                session.openExclusiveWrite("/move-source.txt").use { it.write(sourceBytes) }
                val movePlan = TransferPlan("c".repeat(64),
                    TransferRoute.VERIFIED_REMOTE_RENAME, Operation.MOVE, emptyList())
                check(runCatching {
                    session.movePlannedFile(movePlan, "/move-source.txt", "/synthetic.txt")
                }.isFailure) { "Native rename must reject an existing destination" }
                check(session.openRead("/synthetic.txt").use { it.readBytes() }
                    .contentEquals(payload.toByteArray(StandardCharsets.UTF_8)))
                check(session.openRead("/move-source.txt").use { it.readBytes() }
                    .contentEquals(sourceBytes))
                session.movePlannedFile(movePlan, "/move-source.txt",
                    "/new-dir/move-source.txt")
                check(session.statFile("/new-dir/move-source.txt").size == sourceBytes.size.toLong())
                check(runCatching { session.statFile("/move-source.txt") }.isFailure)
                check(session.openRead("/new-dir/move-source.txt").use { it.readBytes() }
                    .contentEquals(sourceBytes))
                check(SftpPath.childPath("/", "move-source.txt") == "/move-source.txt")
                check(runCatching { SftpPath.childPath("/", "..") }.isFailure)
                // Moving back to root must be legal, collision guarded and
                // byte-preserving. No separate delete or client file relay.
                check(runCatching {
                    session.movePlannedFile(movePlan, "/new-dir/move-source.txt", "/synthetic.txt")
                }.isFailure) { "Root collision must retain both files" }
                check(session.openRead("/new-dir/move-source.txt").use { it.readBytes() }
                    .contentEquals(sourceBytes))
                session.movePlannedFile(movePlan, "/new-dir/move-source.txt",
                    SftpPath.childPath("/", "move-source.txt"))
                check(runCatching { session.statFile("/new-dir/move-source.txt") }.isFailure)
                check(session.openRead("/move-source.txt").use { it.readBytes() }
                    .contentEquals(sourceBytes))
                println("PASS SFTP LAB no-overwrite Move out/back to root, unchanged bytes")
            }
            println("PASS SFTP LAB trusted host fingerprint + authenticated directory read")
            val refused = runCatching {
                SecureSftp.connect(SftpTarget("127.0.0.1", port, wrong),
                    "synthetic", "synthetic-password".toCharArray()).close()
            }
            check(refused.isFailure) { "Unrecognized SFTP host key must be rejected" }
            println("PASS SFTP LAB wrong host fingerprint rejected during handshake")
        } finally {
            runCatching { sshd.stop(true) }
            repeat(205) { index ->
                runCatching { Files.deleteIfExists(many.resolve(
                    "entry-" + index.toString().padStart(4, '0') + ".txt")) }
            }
            runCatching { Files.deleteIfExists(many.resolve("new-after-205")) }
            runCatching { Files.deleteIfExists(many) }
            runCatching { Files.deleteIfExists(tmp.resolve("synthetic.txt")) }
            runCatching { Files.deleteIfExists(tmp.resolve("synthetic-upload.txt")) }
            runCatching { Files.deleteIfExists(tmp.resolve("new-dir/move-source.txt")) }
            runCatching { Files.deleteIfExists(tmp.resolve("move-source.txt")) }
            runCatching { Files.deleteIfExists(tmp.resolve("new-dir")) }
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
