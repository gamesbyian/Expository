package dev.expository.transfer.planner

/** E1-only in-memory workspace tests. No Android, connections or file writes. */
object WorkspaceStateSuite {
    private const val NOW = 1_000L
    private fun caps(): Map<Capability, CapabilityProof> = listOf(
        Capability.READ, Capability.WRITE).associateWith {
        CapabilityProof(Support.SUPPORTED, "fake-observation", NOW + 10_000L)
    }
    private fun observed(kind: LocationKind, authority: String, folder: String,
        revision: String = "rev-1"): LocationRef =
        LocationRef(
            LocationKey(kind, authority, "root", folder),
            folder.split('/').filter { it.isNotEmpty() },
            revision, caps(),
            verifiedNamespace = "verified-" + authority,
            verifiedStorageDomain = "storage-" + authority
        )

    private fun item(location: LocationRef, id: String): ItemRef =
        ItemRef(ItemKey(location.key.authorityId, id, "v1"),
            location, location.folder, id + ".txt", ItemKind.FILE)

    private fun expectRejected(block: () -> Unit) {
        check(runCatching { block() }.isFailure)
    }

    @JvmStatic fun main(args: Array<String>) {
        val local = observed(LocationKind.LOCAL_SAF, "opaque-local", "/Inbox")
        val localNext = observed(LocationKind.LOCAL_SAF, "opaque-local", "/Inbox/Archive")
        val localOther = observed(LocationKind.LOCAL_SAF, "other-grant", "/Inbox")
        val sftp = observed(LocationKind.REMOTE_SFTP, "opaque-sftp", "/destination")
        val ftps = observed(LocationKind.REMOTE_FTPS, "opaque-ftps", "/archive")
        val candidate = item(local, "source")

        // Single is the independent default, not dual A hidden as a tab.
        var state = TransferWorkspaceState()
        check(state.view == WorkspaceView.SINGLE)
        check(state.single.endpoint == BrowseEndpoint.LOCAL)
        check(state.pane(WorkspacePane.A) !== state.pane(WorkspacePane.SINGLE))
        state = state.update(WorkspacePane.SINGLE,
            state.single.visit(local).select(candidate).scroll(9, 13))
        check(state.copyProposal(WorkspacePane.A, null, NOW) == null)

        // SAF-backed document providers can occupy either pane without being
        // misrepresented as an SFTP or Google Drive API session. Grant
        // switching clears authority/selection and preserves planner guards.
        val documents = BrowsePaneState(BrowseEndpoint.DOCUMENTS)
            .visit(local).select(candidate)
        check(documents.endpoint.isSaf && !documents.endpoint.remote)
        check(BrowseEndpoint.DOCUMENTS.accepts(LocationKind.LOCAL_SAF))
        check(!BrowseEndpoint.DOCUMENTS.accepts(LocationKind.DRIVE_API))
        check(!BrowseEndpoint.DOCUMENTS.accepts(LocationKind.REMOTE_SFTP))
        check(documents.endpoint(BrowseEndpoint.LOCAL).current == null)
        val documentToDevice = TransferWorkspaceState(view = WorkspaceView.DUAL,
            a = documents, b = BrowsePaneState(BrowseEndpoint.LOCAL).visit(localOther))
        check(documentToDevice.copyProposal(WorkspacePane.A,
            DestinationListing("scoped-provider-full-list", true, emptyList()),
            NOW)?.route == TransferRoute.DEVICE_RELAY)
        val documentSwap = documentToDevice.swap()
        check(documentSwap.b.endpoint == BrowseEndpoint.DOCUMENTS &&
            documentSwap.b.current?.selected?.single()?.key == candidate.key)
        check(documentToDevice.preset(DualPairing.REMOTE_REMOTE).a.endpoint ==
            BrowseEndpoint.SFTP)
        check(documents.visit(localOther).current?.selected?.isEmpty() == true)

        // The UI must discard the entire former Single location state on
        // switching routes, including a return to the same provider kind.
        val selectedProvider = TransferWorkspaceState(single = documents)
        val switchedSingle = selectedProvider.update(WorkspacePane.SINGLE,
            BrowsePaneState(BrowseEndpoint.SFTP))
        check(switchedSingle.single.endpoint == BrowseEndpoint.SFTP &&
            switchedSingle.single.current == null &&
            switchedSingle.single.back.isEmpty())
        val returnedProvider = switchedSingle.update(WorkspacePane.SINGLE,
            BrowsePaneState(BrowseEndpoint.DOCUMENTS))
        check(returnedProvider.single.endpoint == BrowseEndpoint.DOCUMENTS &&
            returnedProvider.single.current == null &&
            returnedProvider.single.back.isEmpty())
        check(selectedProvider.single.current?.selected?.size == 1)

        // Both dual panes are separately navigable, independently selected and
        // scrolled. Switching layouts cannot erase either workspace.
        state = state.view(WorkspaceView.DUAL)
        state = state.update(WorkspacePane.A, state.a.visit(local).select(candidate).scroll(22, 7))
        state = state.update(WorkspacePane.B, state.b.visit(sftp).scroll(4, 2))
        val previousB = state.b
        state = state.update(WorkspacePane.A, state.a.visit(localNext).scroll(1))
        check(state.a.current?.selected?.isEmpty() == true)
        check(state.a.back.size == 1)
        check(state.b == previousB)
        state = state.update(WorkspacePane.A, state.a.back())
        check(state.a.current?.selected?.single()?.key == candidate.key)
        check(state.a.current?.firstVisibleIndex == 22)
        check(state.a.current?.firstVisibleOffset == 7)
        state = state.update(WorkspacePane.A, state.a.forward())
        check(state.a.current?.location?.folder == localNext.folder)
        state = state.update(WorkspacePane.A, state.a.back())
        state = state.view(WorkspaceView.SINGLE).view(WorkspaceView.DUAL)
        check(state.single.current?.selected?.single()?.key == candidate.key)
        check(state.a.current?.selected?.single()?.key == candidate.key)
        check(state.b == previousB)
        check(state.a.current?.location?.capabilities?.isEmpty() == true)
        val staleHistory = state.copyProposal(WorkspacePane.A,
            DestinationListing("complete-destination-1", true, emptyList()), NOW)
        check(staleHistory != null && !staleHistory.executable)
        check("SOURCE_READ_NOT_PROVEN" in staleHistory.blockers)
        // A fresh provider observation clears old file versions. The user
        // explicitly reselects before any capable plan can be proposed.
        state = state.update(WorkspacePane.A, state.a.visit(local))
        check(state.a.current?.selected?.isEmpty() == true)
        state = state.update(WorkspacePane.A, state.a.select(candidate))
        check(state.a.current?.firstVisibleIndex == 22)

        // Both the future drag path and accessible button produce the exact
        // same immutable Copy plan, with no write and no Move shortcut.
        val listing = DestinationListing("complete-destination-1", true, emptyList())
        val button = state.copyProposal(WorkspacePane.A, listing, NOW)
        val dragged = state.dragCopyProposal(WorkspacePane.A, listing, NOW)
        check(button != null && button == dragged && button.executable)
        check(button.route == TransferRoute.DEVICE_RELAY && button.operation == Operation.COPY)
        check(state.copyProposal(WorkspacePane.A, null, NOW)?.executable == false)
        check(state.copyProposal(WorkspacePane.A,
            DestinationListing("incomplete", false, emptyList()), NOW)?.executable == false)
        check(state.copyProposal(WorkspacePane.A,
            DestinationListing("collision", true, listOf(candidate.name)), NOW)?.executable == false)
        check(state.copyProposal(WorkspacePane.SINGLE, listing, NOW) == null)
        // A Move button can propose a route, but a cross-authority Move
        // may never delete without explicit consent and source capability.
        val refusedMove = state.moveProposal(WorkspacePane.A, listing, NOW)
        check(refusedMove != null && !refusedMove.executable)
        check("DELETE_CONSENT_REQUIRED" in refusedMove.blockers)
        check("DELETE_CAPABILITY_NOT_PROVEN" in refusedMove.blockers)
        check(state.moveProposal(WorkspacePane.A, listing, NOW,
            deletionAuthorized = true)?.executable == false)
        check(state.moveProposal(WorkspacePane.B, listing, NOW) == null)


        // Swap moves each entire identity/path/selection/history, not just
        // text labels. Active A/B tab follows the actual observed location.
        state = state.showDual(WorkspacePane.A)
        val aBefore = state.a
        val bBefore = state.b
        state = state.swap()
        check(state.a == bBefore && state.b == aBefore)
        check(state.activeDual == WorkspacePane.B)
        check(state.copyProposal(WorkspacePane.B, listing, NOW) == button)
        state = state.swap()
        check(state.a == aBefore && state.b == bBefore)
        expectRejected { state.showDual(WorkspacePane.SINGLE) }

        // A different account or grant cannot carry forward selections,
        // scroll/history or remembered capabilities from the prior authority.
        val reset = state.a.visit(localOther)
        check(reset.current?.selected?.isEmpty() == true)
        check(reset.back.isEmpty() && reset.forward.isEmpty())
        check(reset.current?.firstVisibleIndex == 0)
        expectRejected { state.a.visit(sftp) }
        expectRejected { state.a.select(item(localNext, "wrong-folder")) }
        expectRejected { state.a.select(item(localOther, "other-authority")) }
        expectRejected { state.a.scroll(-1) }
        expectRejected { state.a.scroll(0, -1) }
        // Even an identical weak observation revision cannot prove the old
        // selected versions survived a refresh. Scroll can survive safely.
        val refreshed = state.a.visit(local)
        check(refreshed.current?.selected?.isEmpty() == true)
        check(refreshed.current?.firstVisibleIndex == 22)
        val staleVersion = observed(LocationKind.LOCAL_SAF, "opaque-local", "/Inbox", "rev-2")
        check(state.a.visit(staleVersion).current?.selected?.isEmpty() == true)

        // Pairings are shortcuts. They never force plaintext or discard a
        // chosen secure remote protocol that still fits a remote slot.
        state = state.update(WorkspacePane.B,
            state.b.endpoint(BrowseEndpoint.FTPS).visit(ftps))
        state = state.preset(DualPairing.LOCAL_REMOTE)
        check(state.a.endpoint == BrowseEndpoint.LOCAL)
        check(state.b.endpoint == BrowseEndpoint.FTPS)
        check(state.b.current?.location == ftps)
        state = state.preset(DualPairing.REMOTE_REMOTE)
        check(state.a.endpoint == BrowseEndpoint.SFTP)
        check(state.a.current == null)
        check(state.b.endpoint == BrowseEndpoint.FTPS && state.b.current?.location == ftps)
        state = state.preset(DualPairing.LOCAL_LOCAL)
        check(state.a.endpoint == BrowseEndpoint.LOCAL)
        check(state.b.endpoint == BrowseEndpoint.LOCAL && state.b.current == null)
        check(state.single.current?.location == local)

        // Unbounded browsing cannot retain an unbounded private path/history.
        var deep = BrowsePaneState(BrowseEndpoint.LOCAL).visit(local)
        repeat(300) { number ->
            deep = deep.visit(observed(LocationKind.LOCAL_SAF,
                "opaque-local", "/folder-" + number))
        }
        check(deep.back.size == 64)
        repeat(64) { deep = deep.back() }
        check(deep.back.isEmpty())
        check(deep.forward.size == 64)

        // A verified same-server Move may use the remote rename route,
        // while Copy between the same locations remains a byte-copy plan.
        val renameProof = CapabilityProof(Support.SUPPORTED, "remote-rename-observed",
            NOW + 10_000L)
        val remoteFrom = sftp.copy(
            key = sftp.key.copy(containerId = "remote-source"),
            folder = listOf("source"), capabilities = caps() +
                (Capability.RENAME to renameProof))
        val remoteTo = sftp.copy(
            key = sftp.key.copy(containerId = "remote-target"),
            folder = listOf("target"), capabilities = caps() +
                (Capability.RENAME to renameProof))
        val remoteState = TransferWorkspaceState(view = WorkspaceView.DUAL,
            a = BrowsePaneState(BrowseEndpoint.SFTP).visit(remoteFrom)
                .select(item(remoteFrom, "remote-item")),
            b = BrowsePaneState(BrowseEndpoint.SFTP).visit(remoteTo))
        val nativeMove = remoteState.moveProposal(WorkspacePane.A, listing, NOW)
        check(nativeMove != null && nativeMove.executable)
        check(nativeMove.operation == Operation.MOVE &&
            nativeMove.route == TransferRoute.VERIFIED_REMOTE_RENAME)
        check(remoteState.copyProposal(WorkspacePane.A, listing, NOW)?.route ==
            TransferRoute.DEVICE_RELAY)
        check(remoteState.swap().moveProposal(WorkspacePane.B, listing, NOW) == nativeMove)

        // No selection can be resurrected by targeting a different session.
        check(BrowsePaneState(BrowseEndpoint.SFTP).visit(sftp).endpoint(BrowseEndpoint.FTPS)
            .current == null)
        check(state.copyProposal(WorkspacePane.B, listing, NOW) == null)
        println("Workspace E1: single/dual state, three presets, histories, scroll, " +
            "selection isolation, swap, revalidation and button/drag plan parity passed")
    }
}
