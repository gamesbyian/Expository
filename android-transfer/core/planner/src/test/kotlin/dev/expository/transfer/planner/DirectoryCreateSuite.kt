package dev.expository.transfer.planner

/** Pure deterministic SFTP MKDIR preflight; no network, secrets or mutations. */
object DirectoryCreateSuite {
    @JvmStatic fun main(args: Array<String>) {
        val proof = CapabilityProof(Support.SUPPORTED, "pinned-host-session", 600_000L)
        val parent = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, "opaque-session", "verified-root", "/shows"),
            folder = listOf("shows"), revision = "observed-listing-1",
            capabilities = mapOf(Capability.WRITE to proof, Capability.CREATE_CHILD to proof),
            verifiedNamespace = "verified-session", verifiedStorageDomain = "verified-server"
        )
        val listing = DestinationListing("snapshot-1", true, listOf("Episode 01"))
        val req = DirectoryCreateRequest(parent, "New Season", listing)
        var passed = 0
        fun case(name: String, test: () -> Unit) {
            test()
            println("PASS MKDIR " + name)
            passed++
        }
        case("fresh authorized parent creates immutable proposal") {
            val a = DirectoryCreatePlanner.plan(req, 100L)
            check(a.executable && a.blockers.isEmpty())
            check(a.absoluteTarget == "/shows/New Season")
            check(a.digest.length == 64)
            check(a.digest == DirectoryCreatePlanner.plan(req, 100L).digest)
        }
        case("casefold and NFC collisions block") {
            val a = DirectoryCreatePlanner.plan(req.copy(childName = "episode 01"), 100L)
            check(!a.executable && "DIRECTORY_ALREADY_EXISTS" in a.blockers)
            val b = DirectoryCreatePlanner.plan(req.copy(
                childName = "Café", listing = listing.copy(siblings = listOf("Cafe\u0301"))), 100L)
            check(!b.executable && "DIRECTORY_ALREADY_EXISTS" in b.blockers)
        }
        case("unknown or expired create-child authority blocks") {
            val absent = DirectoryCreatePlanner.plan(req.copy(parent = parent.copy(
                capabilities = mapOf(Capability.WRITE to proof))), 100L)
            check("MISSING_CREATE_CHILD_CAPABILITY" in absent.blockers)
            val expired = DirectoryCreatePlanner.plan(req, 600_001L)
            check(!expired.executable)
        }
        case("incomplete listing cannot authorize write") {
            val a = DirectoryCreatePlanner.plan(req.copy(listing = listing.copy(complete = false)), 100L)
            check("PARENT_LISTING_NOT_COMPLETE" in a.blockers)
        }
        case("untrusted namespace or non-SFTP blocks") {
            val unknown = DirectoryCreatePlanner.plan(req.copy(parent = parent.copy(
                verifiedNamespace = null)), 100L)
            check("UNVERIFIED_REMOTE_NAMESPACE" in unknown.blockers)
            val local = DirectoryCreatePlanner.plan(req.copy(parent = parent.copy(
                key = parent.key.copy(kind = LocationKind.LOCAL_SAF))), 100L)
            check("UNVERIFIED_PARENT_IDENTITY" in local.blockers)
        }
        case("traversals and ambiguous names never produce executable plans") {
            for (name in listOf("..", ".", "/a", "a/b", "a\\b", "bad\nname", " ")) {
                check(!DirectoryCreatePlanner.plan(req.copy(childName = name), 100L).executable)
            }
            val huge = "X".repeat(300)
            check(!DirectoryCreatePlanner.plan(req.copy(childName = huge), 100L).executable)
        }
        case("destination drift invalidates plan digest") {
            val original = DirectoryCreatePlanner.plan(req, 100L)
            val changed = DirectoryCreatePlanner.plan(req.copy(
                listing = listing.copy(revision = "snapshot-2")), 100L)
            check(original.digest != changed.digest)
        }
        case("different complete sibling enumeration changes digest") {
            val a = DirectoryCreatePlanner.plan(req, 100L)
            val b = DirectoryCreatePlanner.plan(req.copy(
                listing = listing.copy(siblings = listOf("Episode 01", "Other"))), 100L)
            check(a.digest != b.digest)
        }
        case("500-item sibling preflight remains deterministic") {
            val names = (1..500).map { "Episode %03d".format(it) }
            val a = DirectoryCreatePlanner.plan(req.copy(
                listing = listing.copy(siblings = names)), 100L)
            check(a.executable)
            check(a.digest == DirectoryCreatePlanner.plan(req.copy(
                listing = listing.copy(siblings = names.reversed())), 100L).digest)
        }
        println("PASS MKDIR total=" + passed + " evidence=pure-jvm")
    }
}
