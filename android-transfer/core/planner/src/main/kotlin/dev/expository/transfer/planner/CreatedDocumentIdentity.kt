package dev.expository.transfer.planner

/**
 * Refuse a DocumentsProvider that returns a previously existing document, a
 * source document or a different authority for a supposedly new file.
 * Does not infer filesystem paths from opaque provider document IDs.
 */
object CreatedDocumentIdentity {
    fun isNew(
        returnedAuthority: String?,
        expectedAuthority: String?,
        returnedId: String,
        existingDestinationIds: Set<String>,
        sourceAuthority: String?,
        sourceId: String
    ): Boolean =
        !returnedAuthority.isNullOrBlank() &&
        returnedAuthority == expectedAuthority &&
        returnedId.isNotBlank() &&
        returnedId !in existingDestinationIds &&
        !(sourceAuthority == returnedAuthority && returnedId == sourceId)
}
