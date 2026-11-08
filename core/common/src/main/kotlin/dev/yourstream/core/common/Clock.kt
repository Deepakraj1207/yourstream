package dev.yourstream.core.common

/** Wall clock in epoch millis. Injected so time-based logic (budgets, intervals) is testable. */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
