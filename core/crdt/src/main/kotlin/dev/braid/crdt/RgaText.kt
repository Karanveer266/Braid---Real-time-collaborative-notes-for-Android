package dev.braid.crdt

/**
 * Collaborative plain-text sequence based on RGA (Replicated Growable Array, Roh et al. 2011),
 * stored as run-length encoded blocks.
 *
 * ## Model
 * Every UTF-16 unit has an immutable [CharId]. An insert names the character it goes *after*
 * (its "parent"); a delete tombstones characters by id. Positions are never transmitted, so
 * concurrent edits cannot "shift" each other the way index-based patches do.
 *
 * ## Integration rule
 * To place a new run with first id `X` after parent `p`: start right after `p` and skip every
 * element whose id is greater than `X`. Those are concurrent siblings with priority, or their
 * descendants, which always have even larger ids (a child is always greater than its parent:
 * it either has a fresh HLC timestamp, which is causally later, or extends the parent's run at a
 * larger offset). Stop at the first smaller id. This yields the same order on every replica
 * regardless of delivery order, the convergence property the tests verify by fuzzing.
 *
 * A run inserted by one op is placed atomically and its chars form a chain, so two users typing
 * words concurrently at the same spot never get their letters interleaved.
 *
 * ## Representation
 * `blocks` holds runs in document order. A block is a contiguous id slice
 * `(ts, offset ..< offset+len)` that is either fully visible or fully deleted. Blocks are split
 * lazily when an insert targets a character in the middle or a delete covers part of a block,
 * and *grown* in place when the owner keeps typing at the end of its run. [byTs] indexes blocks
 * per run (sorted by offset) for O(1) run lookup + O(log k) search within the run.
 *
 * Complexity: id lookup O(log k); positional work O(B), B = number of blocks (not characters).
 * Typing 10 000 characters sequentially yields exactly one block. See details.md.
 *
 * Not thread-safe: callers confine each instance to one thread/actor.
 */
class RgaText {

    internal class Block(
        val ts: Timestamp,
        var offset: Int,
        var text: String,
        var deleted: Boolean,
    ) {
        val length: Int get() = text.length
        val end: Int get() = offset + text.length
        val firstId: CharId get() = CharId(ts, offset)
    }

    private val blocks = ArrayList<Block>()
    private val byTs = HashMap<Timestamp, MutableList<Block>>()
    private var cachedText: String? = ""

    /** Number of visible characters. */
    var length: Int = 0
        private set

    /** Number of internal blocks (visible + tombstoned). Exposed for diagnostics and tests. */
    val blockCount: Int get() = blocks.size

    /** Number of tombstoned characters still retained (see "Garbage collection" in details.md). */
    val tombstoneCount: Int get() = blocks.sumOf { if (it.deleted) it.length else 0 }

    val text: String
        get() = cachedText ?: buildString(length) {
            for (b in blocks) if (!b.deleted) append(b.text)
        }.also { cachedText = it }

    override fun toString(): String = text

    // ---------------------------------------------------------------- queries

    fun contains(id: CharId): Boolean = findBlock(id) != null

    /** Id of the visible character at [index]. */
    fun charIdAt(index: Int): CharId {
        require(index in 0 until length) { "index $index out of 0..<$length" }
        var acc = 0
        for (b in blocks) {
            if (b.deleted) continue
            if (index < acc + b.length) return CharId(b.ts, b.offset + (index - acc))
            acc += b.length
        }
        error("unreachable")
    }

    /**
     * A position-independent anchor for a caret placed *before* visible index [index]:
     * the id of the character to its left, or `null` for the very start.
     */
    fun anchorAt(index: Int): CharId? = if (index <= 0 || length == 0) null else charIdAt(minOf(index, length) - 1)

    /**
     * Resolves an anchor from [anchorAt] back to a caret index in the *current* text.
     * If the anchoring character was deleted, the caret sticks to where it used to be.
     */
    fun resolveAnchor(anchor: CharId?): Int {
        if (anchor == null) return 0
        val target = findBlock(anchor) ?: return length
        var acc = 0
        for (b in blocks) {
            if (b === target) return if (b.deleted) acc else acc + (anchor.offset - b.offset) + 1
            if (!b.deleted) acc += b.length
        }
        return acc
    }

    /** The id ranges currently covering visible indices `[start, end)`. Used to build delete ops. */
    fun idRanges(start: Int, end: Int): List<IdRange> {
        require(start in 0..end && end <= length) { "range $start..$end out of 0..$length" }
        if (start == end) return emptyList()
        val out = ArrayList<IdRange>()
        var acc = 0
        for (b in blocks) {
            if (b.deleted) continue
            val bStart = acc
            val bEnd = acc + b.length
            acc = bEnd
            if (bEnd <= start) continue
            if (bStart >= end) break
            val from = maxOf(start, bStart) - bStart
            val to = minOf(end, bEnd) - bStart
            val prev = out.lastOrNull()
            if (prev != null && prev.ts == b.ts && prev.offset + prev.length == b.offset + from) {
                out[out.lastIndex] = prev.copy(length = prev.length + (to - from))
            } else {
                out += IdRange(b.ts, b.offset + from, to - from)
            }
        }
        return out
    }

    /**
     * If [replica] can extend its own run instead of starting a new one when inserting after
     * [after], returns the first id to use; otherwise `null` (use a fresh op timestamp).
     *
     * Allowed only when (1) [after] belongs to a run owned by [replica], (2) it is the last id
     * ever allocated in that run, and (3) the extension would be placed immediately after
     * [after]. Condition (3) matters: if a newer sibling already follows [after], an extension
     * id (smaller than that sibling) would be ordered *after* the sibling, away from the caret.
     */
    fun extensionIdFor(after: CharId?, replica: ReplicaId): CharId? {
        if (after == null || after.ts.replica != replica) return null
        val run = byTs[after.ts] ?: return null
        val last = run.last()
        if (last.end != after.offset + 1) return null
        val candidate = CharId(after.ts, last.end)
        val next = blocks.getOrNull(indexOfBlock(last) + 1)
        if (next != null && next.firstId > candidate) return null
        return candidate
    }

    /**
     * The still-visible parts of an id range, in document order, with their text.
     * Used by undo: undoing an insert must only delete what is still visible, and redoing it
     * must only bring back what was actually removed.
     */
    fun visibleParts(range: IdRange): List<Pair<IdRange, String>> {
        val run = byTs[range.ts] ?: return emptyList()
        val end = range.offset + range.length
        val out = ArrayList<Pair<IdRange, String>>()
        for (b in blocks) {
            if (b.deleted || b.ts != range.ts || b.end <= range.offset || b.offset >= end) continue
            val from = maxOf(b.offset, range.offset)
            val to = minOf(b.end, end)
            out += IdRange(b.ts, from, to - from) to b.text.substring(from - b.offset, to - b.offset)
        }
        check(run.isNotEmpty())
        return out
    }

    /** Visible text split into spans by author, for the attribution ("blame") view. */
    fun authorSpans(): List<AuthorSpan> {
        val out = ArrayList<AuthorSpan>()
        var acc = 0
        for (b in blocks) {
            if (b.deleted) continue
            val last = out.lastOrNull()
            if (last != null && last.author == b.ts.replica && last.end == acc) {
                out[out.lastIndex] = last.copy(end = acc + b.length)
            } else {
                out += AuthorSpan(acc, acc + b.length, b.ts.replica)
            }
            acc += b.length
        }
        return out
    }

    // ---------------------------------------------------------------- integration

    /**
     * Integrates an insert whose first character id is [start]. Idempotent.
     * @return the visible index where the text was placed, or -1 if it was already present.
     * @throws MissingDependencyException if [after] has not been integrated yet.
     */
    fun integrateInsert(start: CharId, after: CharId?, text: String): Int {
        require(text.isNotEmpty()) { "empty insert" }
        if (findBlock(start) != null) return -1
        val run = byTs[start.ts]
        if (run != null && run.last().end != start.offset) {
            throw MissingDependencyException("non-contiguous extension $start of run ending at ${run.last().end}")
        }
        if (run == null && start.offset != 0) throw MissingDependencyException("extension $start of unknown run")

        var parentBlock: Block? = null
        var pos = if (after == null) {
            0
        } else {
            val parent = findBlock(after) ?: throw MissingDependencyException("insert $start after unknown $after")
            val local = after.offset - parent.offset
            if (local < parent.length - 1) split(parent, local + 1)
            parentBlock = parent
            indexOfBlock(parent) + 1
        }
        val firstCandidate = pos
        while (pos < blocks.size && blocks[pos].firstId > start) pos++

        // Fast path: the owner keeps typing at the end of its own visible run -> grow the block.
        if (parentBlock != null && pos == firstCandidate && !parentBlock.deleted &&
            parentBlock.ts == start.ts && parentBlock.end == start.offset
        ) {
            val index = visibleCharsBefore(pos)
            parentBlock.text += text
            length += text.length
            cachedText = null
            return index
        }

        val block = Block(start.ts, start.offset, text, deleted = false)
        blocks.add(pos, block)
        byTs.getOrPut(start.ts) { ArrayList(1) }.add(block)
        length += text.length
        cachedText = null
        return visibleCharsBefore(pos)
    }

    /**
     * Tombstones every character in [ranges]. Idempotent.
     * @return the visible deletions performed, each as (index, count) relative to the text right
     * before that individual deletion, so they can be replayed sequentially onto a caret.
     */
    fun integrateDelete(ranges: List<IdRange>): List<VisibleDeletion> {
        // Validate everything first so a causally invalid op fails atomically (no half-applied
        // delete whose visible effects would never reach the editor). Runs only grow
        // contiguously, so every offset below the run's end exists.
        for (r in ranges) {
            val run = byTs[r.ts] ?: throw MissingDependencyException("delete in unknown run ${r.ts}")
            if (run.last().end < r.offset + r.length) throw MissingDependencyException("delete beyond run ${r.ts}")
        }
        val out = ArrayList<VisibleDeletion>()
        for (r in ranges) {
            var o = r.offset
            val end = r.offset + r.length
            while (o < end) {
                var b = findBlock(CharId(r.ts, o)) ?: throw MissingDependencyException("delete unknown ${r.ts}+$o")
                if (o > b.offset) b = split(b, o - b.offset)
                if (b.end > end) split(b, end - b.offset)
                if (!b.deleted) {
                    out += VisibleDeletion(visibleCharsBefore(indexOfBlock(b)), b.length)
                    b.deleted = true
                    length -= b.length
                    cachedText = null
                }
                o = b.end
            }
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private fun findBlock(id: CharId): Block? {
        val list = byTs[id.ts] ?: return null
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val b = list[mid]
            when {
                id.offset < b.offset -> hi = mid - 1
                id.offset >= b.end -> lo = mid + 1
                else -> return b
            }
        }
        return null
    }

    /** Splits [block] so it keeps `[0, at)`; returns the new right part `[at, len)`. */
    private fun split(block: Block, at: Int): Block {
        require(at in 1 until block.length)
        val right = Block(block.ts, block.offset + at, block.text.substring(at), block.deleted)
        block.text = block.text.substring(0, at)
        blocks.add(indexOfBlock(block) + 1, right)
        val list = byTs.getValue(block.ts)
        list.add(list.indexOfIdentity(block) + 1, right)
        return right
    }

    private fun indexOfBlock(block: Block): Int {
        val i = blocks.indexOfIdentity(block)
        check(i >= 0) { "block not in sequence" }
        return i
    }

    private fun visibleCharsBefore(pos: Int): Int {
        var acc = 0
        for (i in 0 until pos) {
            val b = blocks[i]
            if (!b.deleted) acc += b.length
        }
        return acc
    }

    private fun <T> List<T>.indexOfIdentity(item: T): Int {
        for (i in indices) if (this[i] === item) return i
        return -1
    }
}

/** A run of visible text written by one replica. */
data class AuthorSpan(val start: Int, val end: Int, val author: ReplicaId)

data class VisibleDeletion(val index: Int, val count: Int)

/** An op referenced state that has not been integrated yet (violates causal delivery). */
class MissingDependencyException(message: String) : IllegalStateException(message)
