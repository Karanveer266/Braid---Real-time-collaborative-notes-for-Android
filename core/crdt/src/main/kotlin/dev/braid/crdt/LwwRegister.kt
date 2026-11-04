package dev.braid.crdt

/**
 * Last-writer-wins register. The HLC timestamp is a total order, so ties are impossible and every
 * replica deterministically picks the same winner regardless of delivery order.
 *
 * Used only for small scalar attributes where "merge" has no meaning (a note is either pinned
 * or not). Text is *never* stored in an LWW register because that would silently discard one
 * user's concurrent typing.
 */
class LwwRegister<T>(initial: T) {
    var value: T = initial
        private set
    var timestamp: Timestamp? = null
        private set

    /** Returns true if the write won (changed the register). */
    fun set(newValue: T, ts: Timestamp): Boolean {
        val current = timestamp
        if (current != null && current >= ts) return false
        value = newValue
        timestamp = ts
        return true
    }

    internal fun restore(value: T, ts: Timestamp?) {
        this.value = value
        this.timestamp = ts
    }
}
