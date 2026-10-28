package dev.expository.transfer.planner

/**
 * E1-only scenarios. These fake witnesses prove no Android SAF grant, live server listing,
 * filesystem mutation, folder copy execution or device recovery.
 */
object RecursiveFolderCopySuite {
    private const val NOW = 100L
    private val read = CapabilityProof(Support.SUPPORTED, "observed-read", 1000L)
    private val write = CapabilityProof(Support.SUPPORTED, "observed-write", 1000L)

    private fun location(folder: List<String>, container: String,
        revision: String = "v1", grant: String = "scope-a"): LocationRef =
        LocationRef(LocationKey(LocationKind.LOCAL_SAF, "source", "root", container, grant),
            folder, revision, mapOf(Capability.READ to read))

    private fun child(id: String, name: String, kind: ItemKind) =
        ObservedLocationChild(ItemKey("source", id, "version-" + id), name, kind)

    private fun index(location: LocationRef,
        children: List<ObservedLocationChild>, snapshot: String = "snapshot"): PagedLocationIndex =
        PagedLocationIndex.begin(location).append(LocationPage(location, snapshot,
            requestedToken = null, nextToken = null, children = children, authoritative = true))

    private data class Fixture(
        val root: ItemRef,
        val parentIndex: PagedLocationIndex,
        val rootIndex: PagedLocationIndex,
        val empty: ItemRef,
        val emptyIndex: PagedLocationIndex,
        val destination: LocationRef,
        val request: RecursiveFolderCopyRequest
    )

    private fun fixture(): Fixture {
        val parent = location(listOf("Media"), "media")
        val parentIndex = index(parent, listOf(child("show", "Shows", ItemKind.DIRECTORY)))
        val root = parentIndex.itemRefs()!!.single()
        val showLoc = location(listOf("Media", "Shows"), "show")
        val showIndex = index(showLoc, listOf(
            child("empty", "Empty", ItemKind.DIRECTORY),
            child("file", "Episode01.mkv", ItemKind.FILE)))
        val empty = showIndex.itemRefs()!!.first { it.kind == ItemKind.DIRECTORY }
        val emptyLoc = location(listOf("Media", "Shows", "Empty"), "empty")
        val emptyIndex = index(emptyLoc, emptyList())
        val dest = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, "target", "backup-root", "backups", "target-scope"),
            listOf("Backups"), "target-v1",
            mapOf(Capability.WRITE to write, Capability.CREATE_CHILD to write))
        val request = RecursiveFolderCopyRequest(root, parentIndex,
            listOf(RecursiveFolderObservation(root, showIndex),
                RecursiveFolderObservation(empty, emptyIndex)),
            dest, DestinationListing("target-listing", true, emptyList()))
        return Fixture(root, parentIndex, showIndex, empty, emptyIndex, dest, request)
    }

    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun case(name: String, verify: () -> Unit) {
            verify()
            println("PASS recursive-manifest " + name)
            count++
        }
        fun deny(request: RecursiveFolderCopyRequest, code: String) {
            val result = RecursiveFolderCopyPlanner.plan(request, NOW)
            check(!result.reviewable && code in result.blockers)
            check(result.entries.isEmpty() && result.files == 0 && result.directories == 0)
        }
        val f = fixture()
        case("review includes file and empty folder, without executing a write") {
            val result = RecursiveFolderCopyPlanner.plan(f.request, NOW)
            check(result.reviewable && result.blockers.isEmpty())
            check(result.files == 1 && result.directories == 2)
            check(result.entries.map { it.relativePath } == listOf(
                listOf("Shows"), listOf("Shows", "Empty"),
                listOf("Shows", "Episode01.mkv")))
            check(result.digest.length == 64)
            check(RecursiveFolderCopyPlanner.plan(f.request, NOW).digest == result.digest)
        }
        case("changed nested file version alters entire review digest") {
            val newIndex = index(f.rootIndex.location,
                f.rootIndex.children.map {
                    if (it.kind == ItemKind.FILE) it.copy(
                        key = it.key.copy(version = "modified-file")) else it
                })
            val changed = f.request.copy(descendants = listOf(
                RecursiveFolderObservation(f.root, newIndex),
                RecursiveFolderObservation(f.empty, f.emptyIndex)))
            val previous = RecursiveFolderCopyPlanner.plan(f.request, NOW)
            val next = RecursiveFolderCopyPlanner.plan(changed, NOW)
            check(next.reviewable && previous.digest != next.digest)
        }
        case("provider iteration order does not change approved manifest") {
            val reordered = index(f.rootIndex.location, f.rootIndex.children.reversed())
            val changed = f.request.copy(descendants = listOf(
                RecursiveFolderObservation(f.empty, f.emptyIndex),
                RecursiveFolderObservation(f.root, reordered)))
            check(RecursiveFolderCopyPlanner.plan(changed, NOW).digest ==
                RecursiveFolderCopyPlanner.plan(f.request, NOW).digest)
        }
        case("source parent must attest exact selected root") {
            deny(f.request.copy(folder = f.root.copy(
                key = f.root.key.copy(version = "changed"))), "SOURCE_ROOT_MEMBERSHIP_NOT_PROVEN")
            deny(f.request.copy(sourceParent = PagedLocationIndex.begin(
                f.parentIndex.location)), "SOURCE_ROOT_MEMBERSHIP_NOT_PROVEN")
        }
        case("missing nested empty-folder witness blocks entire tree") {
            deny(f.request.copy(descendants = f.request.descendants.take(1)),
                "DIRECTORY_LISTING_MISSING_OR_STALE")
        }
        case("extra and duplicated directory observations cannot be smuggled in") {
            deny(f.request.copy(descendants = f.request.descendants +
                f.request.descendants.first()), "DUPLICATE_DIRECTORY_WITNESS")
            val alien = f.empty.copy(key = f.empty.key.copy(opaqueItemId = "alien"))
            deny(f.request.copy(descendants = f.request.descendants +
                RecursiveFolderObservation(alien, f.emptyIndex)),
                "UNRELATED_OR_MISSING_DIRECTORY_WITNESS")
        }
        case("grant or real folder binding drift blocks observation") {
            val wrongScope = f.emptyIndex.location.copy(
                key = f.emptyIndex.location.key.copy(grantId = "other"))
            deny(f.request.copy(descendants = listOf(
                f.request.descendants.first(),
                RecursiveFolderObservation(f.empty, index(wrongScope, emptyList())))),
                "DIRECTORY_LISTING_NOT_AUTHORIZED")
            val wrongPath = f.emptyIndex.location.copy(folder = listOf("Unrelated"))
            deny(f.request.copy(descendants = listOf(
                f.request.descendants.first(),
                RecursiveFolderObservation(f.empty, index(wrongPath, emptyList())))),
                "DIRECTORY_LISTING_NOT_AUTHORIZED")
        }
        case("incomplete subfolder listing blocks even if visible rows look fine") {
            val loc = f.rootIndex.location
            val partial = PagedLocationIndex.begin(loc).append(LocationPage(
                loc, "first-only", null, "next", f.rootIndex.children, true))
            deny(f.request.copy(descendants = listOf(
                RecursiveFolderObservation(f.root, partial),
                f.request.descendants.last())), "DIRECTORY_LISTING_NOT_AUTHORIZED")
        }
        case("identity aliases and duplicate IDs never silently copy a tree") {
            val alias = index(f.rootIndex.location, listOf(
                child("empty", "Empty", ItemKind.DIRECTORY),
                child("empty", "Duplicate", ItemKind.FILE)))
            deny(f.request.copy(descendants = listOf(
                RecursiveFolderObservation(f.root, alias),
                f.request.descendants.last())), "DIRECTORY_LISTING_NOT_AUTHORIZED")
        }
        case("destination names and missing create capability block before copy") {
            deny(f.request.copy(destinationListing =
                DestinationListing("target-listing", true, listOf("shows"))),
                "DESTINATION_NAME_COLLISION")
            deny(f.request.copy(destination = f.destination.copy(
                capabilities = mapOf(Capability.WRITE to write))),
                "DESTINATION_CREATE_CHILD_NOT_PROVEN")
            deny(f.request.copy(destinationListing = DestinationListing(
                "target", false, emptyList())), "DESTINATION_LISTING_NOT_VERIFIED")
        }
        case("expired source and destination authority fail closed") {
            check(!RecursiveFolderCopyPlanner.plan(f.request, 1_001L).reviewable)
            deny(f.request.copy(destination = f.destination.copy(
                capabilities = mapOf(
                    Capability.WRITE to CapabilityProof(Support.UNKNOWN, "?", 999L),
                    Capability.CREATE_CHILD to write))), "DESTINATION_WRITE_NOT_PROVEN")
        }
        case("plain FTP cannot acquire folder transfer through synthetic capabilities") {
            val insecure = f.root.copy(location = f.root.location.copy(
                key = f.root.location.key.copy(kind = LocationKind.REMOTE_FTP)))
            deny(f.request.copy(folder = insecure), "PLAINTEXT_FTP_TRANSFER_NOT_AUTHORIZED")
        }
        case("bounded total tree size cannot authorize truncated expansion") {
            val big = (1..2_000).map {
                child("large" + it, "f" + it.toString().padStart(4, '0'), ItemKind.FILE)
            }
            val bigRoot = index(f.rootIndex.location, big +
                child("empty", "Empty", ItemKind.DIRECTORY))
            val withBig = f.request.copy(descendants = listOf(
                RecursiveFolderObservation(f.root, bigRoot),
                f.request.descendants.last()))
            check(RecursiveFolderCopyPlanner.plan(withBig, NOW).reviewable.not())
            check("DIRECTORY_LISTING_NOT_AUTHORIZED" in
                RecursiveFolderCopyPlanner.plan(withBig, NOW).blockers)
        }
        case("same root descendant destination is never a valid Copy") {
            val source = f.root.location
            val self = source.copy(folder = listOf("Media", "Shows", "Subfolder"),
                key = source.key.copy(containerId = "descendant"),
                capabilities = mapOf(Capability.WRITE to write, Capability.CREATE_CHILD to write),
                verifiedNamespace = "same-ns", verifiedStorageDomain = "same-server")
            val folder = f.root.copy(location = source.copy(
                verifiedNamespace = "same-ns", verifiedStorageDomain = "same-server"))
            deny(f.request.copy(folder = folder, destination = self), "SOURCE_TO_DESCENDANT")
        }
        println("PASS recursive-folder-manifest E1 total=" + count)
    }
}
