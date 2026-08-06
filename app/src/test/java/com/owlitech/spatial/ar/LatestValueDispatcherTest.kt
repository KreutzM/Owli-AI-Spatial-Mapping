package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestValueDispatcherTest {
    @Test
    fun pendingValuesAreOverwrittenInsteadOfQueued() {
        val posted = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<Int>()
        val dispatcher = LatestValueDispatcher<Int>(
            post = { posted += it },
            consume = { consumed += it },
        )

        dispatcher.offer(1)
        dispatcher.offer(2)
        dispatcher.offer(3)

        assertEquals(1, posted.size)
        posted.removeAt(0).invoke()
        assertEquals(listOf(3), consumed)
        assertEquals(0, posted.size)
    }
}
