package dev.yourstream.playback.media3

import java.util.concurrent.ConcurrentHashMap

/**
 * Where each byte of a split file lives: the pieces in order, joined, are the original file. A
 * position maps to (piece, offset in it); a position exactly on a boundary belongs to the next
 * piece, so a reader never opens a piece at its very end (pure; tested).
 */
class SplitLayout(val sizes: List<Long>) {
    init { require(sizes.isNotEmpty() && sizes.all { it > 0 }) { "every piece needs its exact size" } }

    val total: Long = sizes.sum()
    private val starts: LongArray = LongArray(sizes.size).also { a -> for (i in 1 until sizes.size) a[i] = a[i - 1] + sizes[i - 1] }

    /** The piece and offset holding [position] (the end of the last piece at [total]). */
    fun locate(position: Long): Pair<Int, Long> {
        require(position in 0..total) { "position $position outside 0..$total" }
        if (position == total) return sizes.lastIndex to sizes.last()
        var lo = 0; var hi = sizes.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= position) lo = mid else hi = mid - 1
        }
        return lo to position - starts[lo]
    }

    fun start(index: Int): Long = starts[index]
}

/**
 * Which piece of a split file is being read right now, by the first piece's file id: the
 * controller steers the download window, focus and statistics of that piece (it only knows the
 * split file as a whole).
 */
object SplitReads {
    private val current = ConcurrentHashMap<Int, Int>()

    fun reading(firstFileId: Int, pieceFileId: Int) { current[firstFileId] = pieceFileId }

    /** The piece being read, or [firstFileId] when nothing is. */
    fun current(firstFileId: Int): Int = current[firstFileId] ?: firstFileId

    fun forget(firstFileId: Int) { current.remove(firstFileId) }
}
