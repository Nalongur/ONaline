package space.privatecanvas.app

import java.util.UUID

data class TextAtom(
    val id: String,
    val afterId: String?,
    val value: String,
    val deleted: Boolean = false,
)

data class TextCrdtDelta(
    val inserts: List<TextAtom>,
    val deletes: List<String>,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && deletes.isEmpty()
}

data class TextCrdtUpdate(
    val atoms: List<TextAtom>,
    val delta: TextCrdtDelta,
)

fun initialTextAtoms(blockId: String, text: String): List<TextAtom> {
    var previousId: String? = null
    return text.toCodePointStrings().mapIndexed { index, value ->
        TextAtom(
            id = "0000000000000-$blockId-${index.toString().padStart(8, '0')}",
            afterId = previousId,
            value = value,
        ).also { previousId = it.id }
    }
}

fun visibleTextAtoms(atoms: List<TextAtom>): List<TextAtom> {
    if (atoms.isEmpty()) return emptyList()
    val byId = atoms.associateBy { it.id }
    val children = atoms.groupBy { atom -> atom.afterId?.takeIf(byId::containsKey) }
        .mapValues { (_, values) -> values.sortedByDescending { it.id } }
    val result = ArrayList<TextAtom>(atoms.size)
    val visited = HashSet<String>(atoms.size)
    val stack = ArrayDeque<TextAtom>()
    children[null].orEmpty().asReversed().forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        val atom = stack.removeLast()
        if (!visited.add(atom.id)) continue
        if (!atom.deleted) result += atom
        children[atom.id].orEmpty().asReversed().forEach(stack::addLast)
    }
    // Malformed or cyclic remote atoms are never allowed to hide valid text.
    atoms.filterNot { it.id in visited }.sortedBy { it.id }.filterNot { it.deleted }.forEach(result::add)
    return result
}

fun renderTextAtoms(atoms: List<TextAtom>): String = visibleTextAtoms(atoms).joinToString("") { it.value }

fun applyLocalTextChange(
    atoms: List<TextAtom>,
    nextText: String,
    deviceId: String,
): TextCrdtUpdate {
    val visible = visibleTextAtoms(atoms)
    val beforeValues = visible.map { it.value }
    val afterValues = nextText.toCodePointStrings()
    var prefix = 0
    while (prefix < beforeValues.size && prefix < afterValues.size && beforeValues[prefix] == afterValues[prefix]) prefix++
    var suffix = 0
    while (
        suffix < beforeValues.size - prefix && suffix < afterValues.size - prefix &&
        beforeValues[beforeValues.lastIndex - suffix] == afterValues[afterValues.lastIndex - suffix]
    ) suffix++

    val deleteAtoms = visible.subList(prefix, beforeValues.size - suffix)
    val deleteIds = deleteAtoms.map { it.id }
    val deletedSet = deleteIds.toHashSet()
    val updated = atoms.map { atom -> if (atom.id in deletedSet) atom.copy(deleted = true) else atom }.toMutableList()
    var anchorId = visible.getOrNull(prefix - 1)?.id
    val nextLogicalCounter = (atoms.maxOfOrNull { atom ->
        atom.id.substringBefore('-').toLongOrNull() ?: 0L
    } ?: 0L) + 1L
    val inserts = afterValues.subList(prefix, afterValues.size - suffix).mapIndexed { index, value ->
        val atom = TextAtom(
            id = "${(nextLogicalCounter + index).toString().padStart(13, '0')}-$deviceId-${UUID.randomUUID()}",
            afterId = anchorId,
            value = value,
        )
        anchorId = atom.id
        updated += atom
        atom
    }
    return TextCrdtUpdate(updated, TextCrdtDelta(inserts, deleteIds))
}

fun applyRemoteTextDelta(atoms: List<TextAtom>, delta: TextCrdtDelta): List<TextAtom> {
    val existingIds = atoms.mapTo(HashSet(atoms.size)) { it.id }
    val deleteIds = delta.deletes.toHashSet()
    val result = atoms.map { atom -> if (atom.id in deleteIds) atom.copy(deleted = true) else atom }.toMutableList()
    delta.inserts.filterNot { it.id in existingIds }.forEach { atom ->
        result += if (atom.id in deleteIds) atom.copy(deleted = true) else atom
    }
    return result
}

private fun String.toCodePointStrings(): List<String> {
    val result = ArrayList<String>(length)
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        result += String(Character.toChars(codePoint))
        index += Character.charCount(codePoint)
    }
    return result
}
