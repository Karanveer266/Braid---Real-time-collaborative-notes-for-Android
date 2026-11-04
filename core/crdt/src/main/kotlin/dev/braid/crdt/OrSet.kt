package dev.braid.crdt

/**
 * Observed-Remove Set with add-wins semantics, used for note tags.
 *
 * Each addition is identified by its op timestamp. A remove carries the set of additions its
 * author had observed and removes only those. Consequently:
 *  - A concurrent add and remove of the same tag resolves to "present" (the remover could not
 *    have intended to delete an addition they never saw). This is the intuitive outcome for tags.
 *  - Re-adding after a remove works (a new addition has a new id), unlike a 2P-Set.
 *
 * Tombstones for removed additions are retained so that the structure stays idempotent even if
 * an op were ever delivered twice.
 */
class OrSet {
    private val additions = HashMap<String, MutableSet<Timestamp>>()
    private val tombstones = HashSet<Timestamp>()

    val elements: Set<String>
        get() = additions.filterValues { it.isNotEmpty() }.keys.toSortedSet()

    operator fun contains(element: String): Boolean = additions[element]?.isNotEmpty() == true

    /** The addition ids a local remove must reference to remove [element] as currently observed. */
    fun observedAdditions(element: String): List<Timestamp> = additions[element]?.sorted().orEmpty()

    fun add(element: String, ts: Timestamp): Boolean {
        if (ts in tombstones) return false
        return additions.getOrPut(element) { HashSet() }.add(ts)
    }

    fun remove(element: String, observed: Collection<Timestamp>): Boolean {
        tombstones += observed
        val set = additions[element] ?: return false
        return set.removeAll(observed.toSet())
    }

    internal fun exportState(): Pair<Map<String, Set<Timestamp>>, Set<Timestamp>> =
        additions.mapValues { it.value.toSet() } to tombstones.toSet()

    internal fun importState(adds: Map<String, Collection<Timestamp>>, tombs: Collection<Timestamp>) {
        additions.clear(); tombstones.clear()
        adds.forEach { (k, v) -> additions[k] = v.toHashSet() }
        tombstones += tombs
    }
}
