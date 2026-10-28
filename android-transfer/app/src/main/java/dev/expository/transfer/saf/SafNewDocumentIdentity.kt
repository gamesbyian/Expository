package dev.expository.transfer.saf

import dev.expository.transfer.TreeListing

/**
 * A DocumentsProvider may choose a different name or unexpectedly return an
 * existing document. Neither result satisfies an explicitly reviewed,
 * collision-free Copy. A rejected create can leave a blank provider document:
 * the attempt journal must remain UNCERTAIN for operator reconciliation.
 *
 * This pure predicate is intentionally not itself authority to write; callers
 * must also check scoped tree/provider identity, grants, source metadata and
 * the immutable operation plan against fresh provider observations.
 */
internal object SafNewDocumentIdentity {
    fun matches(expectedName: String, observedName: String,
        newDocumentId: String, existingDocumentIds: Set<String>,
        forbiddenDocumentIds: Set<String> = emptySet()): Boolean =
        expectedName.isNotBlank() &&
            observedName == expectedName &&
            newDocumentId.isNotBlank() &&
            newDocumentId !in existingDocumentIds &&
            newDocumentId !in forbiddenDocumentIds


    /**
     * A missing/moved/renamed child after streaming is a positive integrity
     * failure, not ordinary lack of readback support. Throwing causes the
     * foreground engine to report UNCERTAIN and the durable journal to require
     * operator reconciliation. Never delete or silently retry the source.
     */
    fun requireDirectChildForReadback(
        expectedName: String, newDocumentId: String, parent: TreeListing
    ) {
        if (!isDirectChild(expectedName, newDocumentId, parent))
            throw java.io.IOException("SAF_DESTINATION_PARENT_UNVERIFIED")
    }

    /** A returned content URI must actually be the new file in the chosen parent. */
    fun isDirectChild(expectedName: String, newDocumentId: String,
        parent: TreeListing): Boolean =
        parent.complete && expectedName.isNotBlank() && newDocumentId.isNotBlank() &&
            parent.children.count { it.documentId == newDocumentId } == 1 &&
            parent.children.count {
                SafFolderPolicy.normalized(it.title) ==
                    SafFolderPolicy.normalized(expectedName)
            } == 1 &&
            parent.children.any { child ->
                child.documentId == newDocumentId &&
                    child.title == expectedName && !child.directory
            }
}
