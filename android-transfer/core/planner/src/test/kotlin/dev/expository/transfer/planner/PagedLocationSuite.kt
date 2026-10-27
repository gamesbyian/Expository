package dev.expository.transfer.planner

/** Pure E1 fixtures: no Android grants, GitHub requests, Drive accounts or writes. */
object PagedLocationSuite {
    private const val NOW = 1_000L

    private fun loc(kind: LocationKind, authority: String, grant: String = "grant-a",
        folder: List<String> = listOf("folder")): LocationRef {
        val proof = CapabilityProof(Support.SUPPORTED, "fake-capability", NOW + 10_000L)
        return LocationRef(
            LocationKey(kind, authority, "synthetic-root", "synthetic-folder", grant),
            folder, "snapshot-parent-v1",
            mapOf(Capability.READ to proof, Capability.WRITE to proof),
            verifiedNamespace = null, verifiedStorageDomain = null
        )
    }

    private fun child(location: LocationRef, number: Int,
        name: String = "File" + number.toString().padStart(4, '0') + ".bin") =
        ObservedLocationChild(
            ItemKey(location.key.authorityId, "opaque-id-" + number, "version-1"),
            name, ItemKind.FILE
        )

    private class FakePages(
        override val kind: LocationKind, private val contents: List<ObservedLocationChild>
    ) : LocationListingAdapter {
        override fun listPage(location: LocationRef, requestedToken: String?,
            maxItems: Int): LocationPage {
            require(location.key.kind == kind && maxItems in 1..PagedLocationIndex.MAX_PAGE)
            val start = requestedToken?.toIntOrNull() ?: 0
            require(start in 0..contents.size)
            val rows = contents.drop(start).take(maxItems)
            val after = start + rows.size
            return LocationPage(location, "synthetic-snapshot-1", requestedToken,
                if (after < contents.size) after.toString() else null,
                rows, authoritative = true)
        }
    }

    private fun scan(location: LocationRef, contents: List<ObservedLocationChild>,
        pageSize: Int = 200): PagedLocationIndex {
        val adapter = FakePages(location.key.kind, contents)
        var index = PagedLocationIndex.begin(location)
        var pages = 0
        do {
            val page = adapter.listPage(location, index.nextToken, pageSize)
            index = index.append(page)
            pages++
            check(pages <= 11 && index.state != LocationIndexState.INVALID)
        } while (index.state == LocationIndexState.OPEN)
        return index
    }

    @JvmStatic fun main(args: Array<String>) {
        val local = loc(LocationKind.LOCAL_SAF, "local-provider")
        val elements = (1..405).map { child(local, it) }
        var index = PagedLocationIndex.begin(local)
        val adapter = FakePages(LocationKind.LOCAL_SAF, elements)
        val first = adapter.listPage(local, null, 200)
        index = index.append(first)
        check(index.state == LocationIndexState.OPEN)
        check(index.children.size == 200 && index.destinationListing() == null &&
            index.itemRefs() == null)
        // A UI-visible first page does not authorize absence/collision checks.
        val second = adapter.listPage(local, index.nextToken, 200)
        index = index.append(second)
        check(index.state == LocationIndexState.OPEN &&
            index.destinationListing() == null)
        val third = adapter.listPage(local, index.nextToken, 200)
        index = index.append(third)
        check(index.state == LocationIndexState.COMPLETE &&
            index.children.size == 405 &&
            index.itemRefs()?.size == 405 &&
            index.destinationListing()?.siblings?.size == 405)
        check(index.append(third).state == LocationIndexState.INVALID)

        val fullLimit = scan(local, (1..2_000).map { child(local, it) })
        check(fullLimit.state == LocationIndexState.COMPLETE &&
            fullLimit.children.size == PagedLocationIndex.MAX_ITEMS)
        val tooMany = scan(local, (1..2_000).map { child(local, it) }
            ).append(LocationPage(local, "synthetic-snapshot-1",
                null, null, listOf(child(local, 2_001)), true))
        check(tooMany.state == LocationIndexState.INVALID)
        val overLimitRows = PagedLocationIndex.begin(local).append(
            LocationPage(local, "v1", null, null,
                (1..201).map { child(local, it) }, true))
        check(overLimitRows.state == LocationIndexState.INVALID &&
            overLimitRows.destinationListing() == null)

        val firstOnly = PagedLocationIndex.begin(local)
        val snap = LocationPage(local, "v1", null, "page-two",
            listOf(child(local, 1)), true)
        val started = firstOnly.append(snap)
        check(started.state == LocationIndexState.OPEN)
        fun blocked(page: LocationPage, prior: PagedLocationIndex = started) {
            val denied = prior.append(page)
            check(denied.state == LocationIndexState.INVALID &&
                denied.destinationListing() == null && denied.itemRefs() == null)
        }
        blocked(snap) // replayed first-page cursor
        blocked(snap.copy(location = local.copy(revision = "new-revision"),
            requestedToken = "page-two", nextToken = null))
        blocked(snap.copy(location = local.copy(
            key = local.key.copy(grantId = "revoked-grant")),
            requestedToken = "page-two", nextToken = null))
        blocked(snap.copy(snapshotId = "different-snapshot",
            requestedToken = "page-two", nextToken = null))
        blocked(snap.copy(requestedToken = "page-two", nextToken = "page-two"))
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            authoritative = false))
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            children = listOf(child(local, 2, "FILE0001.bin")))) // normalized name
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            children = listOf(child(local, 1, "Other.bin")))) // same opaque id
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            children = listOf(child(local, 2).copy(name = "wrong/child"))))
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            children = listOf(child(local, 2).copy(
                key = ItemKey("other-provider", "id-2", "v1")))))
        blocked(snap.copy(requestedToken = "page-two", nextToken = null,
            children = listOf(child(local, 2).copy(
                key = ItemKey(local.key.authorityId, "id-2", "")))))
        check(PagedLocationIndex.begin(local).append(
            snap.copy(nextToken = "loop", children = emptyList())).state ==
            LocationIndexState.INVALID)

        // Model pairings without granting any Android/Drive/GitHub authority.
        // Backend-specific adapters and verified byte receipts are still needed.
        val git = loc(LocationKind.GITHUB_REPOSITORY, "public-github", grant = "")
        val sftp = loc(LocationKind.REMOTE_SFTP, "pinned-sftp")
        val drive = loc(LocationKind.DRIVE_SAF, "drive-scoped-provider")
        val gitRead = scan(git, listOf(child(git, 1)))
        val sftpEmpty = scan(sftp, emptyList())
        val driveEmpty = scan(drive, emptyList())
        val githubToSftp = OperationPlanner.transfer(
            TransferRequest(gitRead.itemRefs()!!, sftp,
                destinationListing = sftpEmpty.destinationListing()), NOW)
        check(githubToSftp.executable &&
            githubToSftp.route == TransferRoute.DEVICE_RELAY)
        val sftpToDrive = OperationPlanner.transfer(
            TransferRequest(scan(sftp, listOf(child(sftp, 1))).itemRefs()!!, drive,
                destinationListing = driveEmpty.destinationListing()), NOW)
        check(sftpToDrive.executable &&
            sftpToDrive.route == TransferRoute.DEVICE_RELAY)
        val gitTarget = OperationPlanner.transfer(
            TransferRequest(scan(sftp, listOf(child(sftp, 2))).itemRefs()!!, git,
                destinationListing = scan(git, emptyList()).destinationListing()), NOW)
        check(!gitTarget.executable && "GITHUB_COMMIT_REVIEW_REQUIRED" in gitTarget.blockers)
        val githubMove = OperationPlanner.transfer(
            TransferRequest(gitRead.itemRefs()!!, drive, Operation.MOVE,
                deletionAuthorized = true,
                destinationListing = driveEmpty.destinationListing()), NOW)
        check(!githubMove.executable && "GITHUB_MOVE_OUT_OF_SCOPE" in githubMove.blockers)

        println("PASS E1 paged location index: authority, snapshot, cursor, " +
            "identity, limit, collision, and scoped cross-provider plan guards")
    }
}
