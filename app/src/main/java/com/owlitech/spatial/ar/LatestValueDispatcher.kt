package com.owlitech.spatial.ar

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single-slot latest-value dispatcher. Offers overwrite stale pending values instead of queueing. */
class LatestValueDispatcher<T : Any>(
    private val post: (() -> Unit) -> Unit,
    private val consume: (T) -> Unit,
) {
    private val latest = AtomicReference<T?>(null)
    private val drainScheduled = AtomicBoolean(false)

    fun offer(value: T) {
        latest.set(value)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        post(::drain)
    }

    private fun drain() {
        latest.getAndSet(null)?.let(consume)
        drainScheduled.set(false)
        if (latest.get() != null) scheduleDrain()
    }
}
