package dev.expository.transfer.planner

/** Same-server native Move policy, synthetic opaque identities only. */
object SftpMoveSuite {
    @JvmStatic fun main(args: Array<String>) {
        val capability = CapabilityProof(Support.SUPPORTED, "verified-pinned-server",
            600_000L)
        val from = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, "pinned-session", "root", "/origin"),
            listOf("origin"), "file-snapshot",
            mapOf(Capability.READ to capability, Capability.RENAME to capability),
            verifiedNamespace = "authenticated-namespace",
            verifiedStorageDomain = "same-server"
        )
        val to = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, "pinned-session", "root", "/destination"),
            listOf("destination"), "dest-snapshot",
            mapOf(Capability.WRITE to capability, Capability.RENAME to capability),
            verifiedNamespace = "authenticated-namespace",
            verifiedStorageDomain = "same-server"
        )
        val file = ItemRef(ItemKey("pinned-session", "/origin/file.txt", "metadata-v1"),
            from, from.folder, "file.txt", ItemKind.FILE)
        val request = TransferRequest(listOf(file), to, operation = Operation.MOVE,
            deletionAuthorized = true,
            destinationListing = DestinationListing("complete-siblings", true, emptyList()))
        var passed = 0
        fun checkCase(name: String, test: () -> Unit) {
            test()
            passed++
            println("PASS SFTP_MOVE " + name)
        }
        checkCase("same authenticated storage selects direct rename without relay") {
            val plan = OperationPlanner.transfer(request, 100L)
            check(plan.executable)
            check(plan.route == TransferRoute.VERIFIED_REMOTE_RENAME)
        }
        checkCase("Copy must never exploit destructive rename path") {
            val copy = OperationPlanner.transfer(request.copy(operation = Operation.COPY), 100L)
            check(copy.route != TransferRoute.VERIFIED_REMOTE_RENAME)
            check(copy.operation == Operation.COPY)
        }
        checkCase("different server domain denies direct rename") {
            val other = to.copy(verifiedStorageDomain = "other-server")
            val plan = OperationPlanner.transfer(request.copy(destination = other), 100L)
            check(plan.route != TransferRoute.VERIFIED_REMOTE_RENAME)
        }
        checkCase("stale rename capability denies direct mutation") {
            val plan = OperationPlanner.transfer(request, 600_001L)
            check(!plan.executable)
        }
        checkCase("destination collision blocks all Move routes") {
            val changed = request.copy(destinationListing = DestinationListing(
                "updated", true, listOf("FILE.TXT")))
            check(!OperationPlanner.transfer(changed, 100L).executable)
        }
        checkCase("unknown parent enumeration forbids rename") {
            val partial = request.copy(destinationListing = DestinationListing(
                "partial", false, emptyList()))
            check(!OperationPlanner.transfer(partial, 100L).executable)
        }
        checkCase("drifted item version changes immutable plan") {
            val initial = OperationPlanner.transfer(request, 100L)
            val changed = request.copy(sources = listOf(file.copy(
                key = file.key.copy(version = "metadata-v2"))))
            check(initial.digest != OperationPlanner.transfer(changed, 100L).digest)
        }
        checkCase("different native root identities deny fast path") {
            val plan = OperationPlanner.transfer(request.copy(destination =
                to.copy(key = to.key.copy(rootId = "different-root"))), 100L)
            check(plan.route != TransferRoute.VERIFIED_REMOTE_RENAME)
        }
        println("PASS SFTP_MOVE total=" + passed + " evidence=pure-jvm")
    }
}
