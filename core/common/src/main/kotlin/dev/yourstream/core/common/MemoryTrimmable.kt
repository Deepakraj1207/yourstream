package dev.yourstream.core.common

/**
 * Something holding rebuildable memory (caches, indexes). The app calls it when Android reports
 * memory pressure; [critical] = the process is likely to be killed next, drop everything.
 */
public fun interface MemoryTrimmable {
    public fun trimMemory(critical: Boolean)
}

/**
 * Process-wide registry: caches register when they are created (weakly, so registration never
 * keeps them alive), and the application forwards Android's memory callbacks here.
 */
public object MemoryPressure {
    private val entries = mutableListOf<java.lang.ref.WeakReference<MemoryTrimmable>>()

    public fun register(t: MemoryTrimmable): Unit = synchronized(entries) { entries += java.lang.ref.WeakReference(t) }

    /** Returns how many caches were trimmed. Failures in one cache never stop the others. */
    public fun trim(critical: Boolean): Int {
        val live = synchronized(entries) { entries.removeAll { it.get() == null }; entries.mapNotNull { it.get() } }
        live.forEach { runCatching { it.trimMemory(critical) } }
        return live.size
    }
}
