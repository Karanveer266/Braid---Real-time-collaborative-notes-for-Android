package dev.braid.crdt

/**
 * Turns "old text → new text" (what a UI text field reports) into a single splice.
 *
 * A text field reports whole new values, not edit intents. Every real keystroke, paste, cut,
 * autocorrect or IME composition replaces exactly one contiguous region, so trimming the common
 * prefix and suffix recovers the user's edit exactly in O(n) without a full Myers diff.
 *
 * Surrogate safety: the split points are moved so a surrogate pair (emoji, many CJK ext. chars)
 * is never cut in half. Otherwise two concurrent edits could each keep one half of a pair and
 * produce invalid UTF-16 on every replica.
 */
data class Splice(val start: Int, val deleteCount: Int, val inserted: String) {
    val isNoop: Boolean get() = deleteCount == 0 && inserted.isEmpty()

    companion object {
        /** [caretHint] (the new caret position) disambiguates edits inside repeated text. */
        fun between(old: String, new: String, caretHint: Int = -1): Splice {
            if (old == new) return Splice(0, 0, "")
            val maxPrefix = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

            // Typing "a" into "aa" at the caret should insert at the caret, not at the end.
            if (caretHint >= 0 && new.length > old.length) {
                val insertedLen = new.length - old.length
                val candidate = caretHint - insertedLen
                if (candidate in 0 until prefix &&
                    old.regionMatches(candidate, new, caretHint, old.length - candidate)
                ) {
                    prefix = candidate
                }
            }

            var suffix = 0
            val maxSuffix = minOf(old.length, new.length) - prefix
            while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

            // Never split a surrogate pair.
            while (prefix > 0 && Character.isHighSurrogate(old.getOrElse(prefix - 1) { ' ' }) ) prefix--
            while (suffix > 0 && Character.isLowSurrogate(old.getOrElse(old.length - suffix) { ' ' })) suffix--

            return Splice(prefix, old.length - prefix - suffix, new.substring(prefix, new.length - suffix))
        }
    }

    fun applyTo(text: String): String = text.substring(0, start) + inserted + text.substring(start + deleteCount)
}
