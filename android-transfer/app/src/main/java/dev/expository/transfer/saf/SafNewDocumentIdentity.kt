package dev.expository.transfer.saf

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
        newDocumentId: String, existingDocumentIds: Set<String>): Boolean =
        expectedName.isNotBlank() &&
            observedName == expectedName &&
            newDocumentId.isNotBlank() &&
            newDocumentId !in existingDocumentIds
}
