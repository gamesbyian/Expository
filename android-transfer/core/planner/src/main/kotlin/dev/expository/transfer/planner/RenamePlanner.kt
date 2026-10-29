package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class RenameItem(val id: String, val name: String, val version: String, val kind: ItemKind)
data class RenameSnapshot(
    val location: LocationRef,
    val children: List<RenameItem>,  // complete parent listing, selected and unselected
    val selectionOrder: List<String>, // frozen visible sort order, NOT map iteration order
    val listingRevision: String,
    val complete: Boolean
)

sealed class RenameEdit {
    data class Replace(val find: String, val replacement: String) : RenameEdit()
    data class Prefix(val value: String) : RenameEdit()
    data class Suffix(val value: String) : RenameEdit()
    data class ChangeCase(val upper: Boolean) : RenameEdit()
    /** Opt-in, conservative removal of empty brackets and copy suffixes. */
    data object CleanUp : RenameEdit()
    data class Number(val start: Long = 1, val step: Long = 1, val width: Int = 2,
                      val separator: String = " ", val prefix: Boolean = false) : RenameEdit()
}
data class RenameRecipe(
    val edits: List<RenameEdit>,
    val extensionsLocked: Boolean = true
)
data class RenamePreviewRow(val id: String, val original: String, val proposed: String)
enum class RenamePhase { STAGE, COMMIT }
data class RenameStep(val itemId: String, val from: String, val to: String, val phase: RenamePhase)
data class RenamePlan(
    val digest: String,
    val preview: List<RenamePreviewRow>,
    val steps: List<RenameStep>,
    val blockers: List<String>,
    val snapshotRevision: String
) {
    val executable get() = blockers.isEmpty()
    val changed get() = preview.count { it.original != it.proposed }
}

private fun nameKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

// Conservative across case-sensitive, case-insensitive and unknown providers.
// False-positive conflict is preferable to a destructive case/Unicode collision.
private fun renameValid(name: String): Boolean {
    val size = name.toByteArray(StandardCharsets.UTF_8).size
    return name.isNotBlank() && name != "." && name != ".." && size <= 255 &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
}

private fun splitExtension(name: String, kind: ItemKind, extensionsLocked: Boolean): Pair<String, String> {
    if (kind == ItemKind.DIRECTORY || !extensionsLocked) return name to ""
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

/**
 * A narrow, deterministic cleanup preset. Never strip numbered years,
 * resolution tags, meaningful punctuation or extensions by default.
 * Caller still receives the full collision/name validation in RenamePlanner.
 */
private fun cleanUpName(stem: String): String =
    stem
        .replace(Regex("""\\s*\\(\\s*\\)\\s*"""), " ")
        .replace(Regex("""\\s*\\[\\s*]\\s*"""), " ")
        .replace(Regex("""\\s*\\{\\s*}\\s*"""), " ")
        .replace(Regex("""(?i)\\s*\\(\\s*copy(?:\\s+\\d+)?\\s*\\)\\s*"""), " ")
        .replace(Regex("""\\s+"""), " ")
        .trim()

private fun applyEdits(item: RenameItem, recipe: RenameRecipe, ordinal: Int): String {
    val (stem, extension) = splitExtension(item.name, item.kind, recipe.extensionsLocked)
    var text = stem
    for (edit in recipe.edits) {
        text = when (edit) {
            is RenameEdit.Replace -> if (edit.find.isEmpty()) text else text.replace(edit.find, edit.replacement)
            is RenameEdit.Prefix -> edit.value + text
            is RenameEdit.Suffix -> text + edit.value
            is RenameEdit.ChangeCase -> if (edit.upper) text.uppercase(Locale.ROOT) else text.lowercase(Locale.ROOT)
            RenameEdit.CleanUp -> cleanUpName(text)
            is RenameEdit.Number -> {
                require(edit.width in 1..12 && edit.step > 0 && edit.start >= 0)
                val number = Math.addExact(edit.start, Math.multiplyExact(ordinal.toLong(), edit.step))
                val label = number.toString().padStart(edit.width, '0')
                if (edit.prefix) label + edit.separator + text else text + edit.separator + label
            }
        }
    }
    return text + extension
}

private fun renameDigest(snapshot: RenameSnapshot, recipe: RenameRecipe): String {
    val md = MessageDigest.getInstance("SHA-256")
    val fields = mutableListOf("RENAME_V1", snapshot.location.key.kind.name,
        snapshot.location.key.authorityId, snapshot.location.key.rootId,
        snapshot.location.key.containerId, snapshot.location.key.grantId.orEmpty(),
        snapshot.location.revision, snapshot.location.verifiedNamespace.orEmpty(),
        snapshot.location.verifiedStorageDomain.orEmpty(), snapshot.listingRevision, snapshot.complete.toString(),
        snapshot.location.casePolicy.name, snapshot.location.folder.size.toString())
    fields += snapshot.location.folder
    for ((op, proof) in snapshot.location.capabilities.toSortedMap(compareBy { it.name })) {
        fields += listOf(op.name, proof.support.name, proof.observationId, proof.expiresAtMillis.toString())
    }
    fields += snapshot.children.size.toString()
    for (row in snapshot.children) fields += listOf(row.id, row.name, row.version, row.kind.name)
    fields += snapshot.selectionOrder.size.toString()
    fields += snapshot.selectionOrder
    fields += recipe.extensionsLocked.toString()
    fields += recipe.edits.size.toString()
    for (edit in recipe.edits) fields += edit.toString()
    for (field in fields) {
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        md.update(':'.code.toByte())
        md.update(bytes)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

object RenamePlanner {
    fun preview(snapshot: RenameSnapshot, recipe: RenameRecipe, nowMillis: Long): RenamePlan {
        val frozen = snapshot.copy(
            location = snapshot.location.copy(
                folder = snapshot.location.folder.toList(),
                capabilities = snapshot.location.capabilities.toMap()
            ),
            children = snapshot.children.toList(),
            selectionOrder = snapshot.selectionOrder.toList()
        )
        val stableRecipe = recipe.copy(edits = recipe.edits.toList())
        val digest = renameDigest(frozen, stableRecipe)
        val blockers = mutableListOf<String>()
        if (!frozen.complete || frozen.listingRevision.isBlank()) blockers += "INCOMPLETE_PARENT_LISTING"
        val proof = frozen.location.capabilities[Capability.RENAME]
        if (proof?.valid(nowMillis) != true) blockers += "RENAME_CAPABILITY_NOT_PROVEN"
        if (frozen.selectionOrder.isEmpty()) blockers += "EMPTY_SELECTION"
        if (frozen.children.map { it.id }.toSet().size != frozen.children.size)
            blockers += "DUPLICATE_SIBLING_ID"
        if (frozen.children.any { it.version.isBlank() || it.id.isBlank() || !renameValid(it.name) })
            blockers += "INVALID_SOURCE_NAME_OR_VERSION"

        val byId = frozen.children.associateBy { it.id }
        if (frozen.selectionOrder.toSet().size != frozen.selectionOrder.size ||
            frozen.selectionOrder.any { it !in byId }) blockers += "INVALID_SELECTION"
        val preview = mutableListOf<RenamePreviewRow>()
        frozen.selectionOrder.forEachIndexed { index, id ->
            val item = byId[id] ?: return@forEachIndexed
            val target = try { applyEdits(item, stableRecipe, index) }
                         catch (e: ArithmeticException) {
                             blockers += "COUNTER_OVERFLOW"; item.name
                         } catch (e: IllegalArgumentException) {
                             blockers += "INVALID_RECIPE"; item.name
                         }
            preview += RenamePreviewRow(item.id, item.name, target)
            if (!renameValid(target)) blockers += "INVALID_DESTINATION_NAME"
        }
        val targets = preview.map { nameKey(it.proposed) }
        if (targets.size != targets.toSet().size) blockers += "DUPLICATE_DESTINATIONS"
        val selected = frozen.selectionOrder.toSet()
        val byPreviewId = preview.associateBy { it.id }
        val unchangedOrUnselected = frozen.children.filter {
            it.id !in selected || byPreviewId[it.id]?.let { p -> p.original == p.proposed } == true
        }.map { nameKey(it.name) }.toSet()
        if (preview.any { it.original != it.proposed && nameKey(it.proposed) in unchangedOrUnselected })
            blockers += "OCCUPIED_DESTINATION"
        // Existing ambiguous sibling spellings must block any write.
        val siblingKeys = frozen.children.map { nameKey(it.name) }
        if (siblingKeys.size != siblingKeys.toSet().size) blockers += "AMBIGUOUS_SIBLING_CASE_UNICODE"
        if (blockers.isNotEmpty()) return RenamePlan(digest, preview, emptyList(), blockers.distinct(),
            frozen.listingRevision)

        val pending = linkedMapOf<String, RenamePreviewRow>()
        for (row in preview) if (row.original != row.proposed) pending[row.id] = row
        val occupied = frozen.children.associate { nameKey(it.name) to it.id }.toMutableMap()
        val reserved = (frozen.children.map { nameKey(it.name) } + targets).toMutableSet()
        val current = pending.mapValues { it.value.original }.toMutableMap()
        val targetOwner = pending.values.associate { nameKey(it.proposed) to it.id }
        val ready = ArrayDeque<String>()
        val candidatesForStage = ArrayDeque(pending.keys)
        pending.values.forEach { row ->
            if (nameKey(row.proposed) !in occupied) ready.addLast(row.id)
        }
        val steps = mutableListOf<RenameStep>()
        var stageIndex = 0
        val staged = mutableSetOf<String>()
        // O(n) graph traversal: every freed name wakes its unique waiting owner.
        // This avoids repeated full scans at the 10k-row acceptance scale.
        while (pending.isNotEmpty()) {
            val readyId = if (ready.isEmpty()) null else ready.removeFirst()
            if (readyId != null) {
                val available = pending[readyId] ?: continue
                if (nameKey(available.proposed) in occupied) continue
                val from = current.getValue(available.id)
                steps += RenameStep(available.id, from, available.proposed, RenamePhase.COMMIT)
                occupied.remove(nameKey(from))
                occupied[nameKey(available.proposed)] = available.id
                pending.remove(available.id)
                targetOwner[nameKey(from)]?.let { if (it in pending) ready.addLast(it) }
                continue
            }
            var stageId: String? = null
            while (candidatesForStage.isNotEmpty()) {
                val candidate = candidatesForStage.removeFirst()
                if (candidate in pending && candidate !in staged) {
                    stageId = candidate
                    break
                }
            }
            if (stageId == null) {
                blockers += "UNRESOLVABLE_RENAME_CYCLE"
                break
            }
            if (frozen.location.capabilities[Capability.TEMP_RENAME]?.valid(nowMillis) != true) {
                blockers += "TEMP_RENAME_CAPABILITY_NOT_PROVEN"
                break
            }
            var temp: String
            do {
                temp = ".expository-tmp-" + digest.take(12) + "-" + stageIndex++
            } while (nameKey(temp) in reserved)
            val from = current.getValue(stageId)
            steps += RenameStep(stageId, from, temp, RenamePhase.STAGE)
            occupied.remove(nameKey(from))
            occupied[nameKey(temp)] = stageId
            reserved += nameKey(temp)
            current[stageId] = temp
            staged += stageId
            targetOwner[nameKey(from)]?.let { if (it in pending) ready.addLast(it) }
        }
        return RenamePlan(digest, preview, if (blockers.isEmpty()) steps else emptyList(),
            blockers.distinct(), frozen.listingRevision)
    }

    fun revalidate(original: RenamePlan, freshSnapshot: RenameSnapshot,
                   recipe: RenameRecipe, nowMillis: Long): Boolean {
        val fresh = preview(freshSnapshot, recipe, nowMillis)
        return original.executable && fresh.executable &&
            original.digest == fresh.digest && original.steps == fresh.steps
    }
}
