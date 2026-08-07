package com.owlitech.spatial.ar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionCloseSchedulerTest {
    @Test
    fun closeIsQueuedAndSlotRemainsUnavailableUntilNativeCloseCompletes() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val session = CloseOnlySession()
        var completionError: Throwable? = IllegalStateException("not completed")

        assertTrue(scheduler.tryAcquireSessionSlot())
        scheduler.scheduleClose(session) { completionError = it }

        assertEquals(0, session.closeCalls)
        assertEquals(DiagnosticSessionSlotState.CLOSING, scheduler.currentSlotState())
        assertFalse(scheduler.tryAcquireSessionSlot())
        assertEquals(1, closeTasks.tasks.size)

        closeTasks.runNext()

        assertEquals(1, session.closeCalls)
        assertEquals(null, completionError)
        assertEquals(DiagnosticSessionSlotState.AVAILABLE, scheduler.currentSlotState())
        assertTrue(scheduler.tryAcquireSessionSlot())
        scheduler.releaseSessionSlot()
    }

    @Test
    fun nativeCloseRunsOnWorkerNotLifecycleCaller() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-session-close")
        }
        try {
            val scheduler = BoundedDiagnosticSessionCloseScheduler(executor, DirectExecutor)
            val closeStarted = CountDownLatch(1)
            val allowCloseToFinish = CountDownLatch(1)
            val completion = CountDownLatch(1)
            val lifecycleThread = Thread.currentThread()
            var closeThread: Thread? = null
            val session = object : CloseOnlySession() {
                override fun close() {
                    closeThread = Thread.currentThread()
                    closeStarted.countDown()
                    assertTrue(allowCloseToFinish.await(5, TimeUnit.SECONDS))
                    super.close()
                }
            }

            assertTrue(scheduler.tryAcquireSessionSlot())
            scheduler.scheduleClose(session) { completion.countDown() }

            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertNotEquals(lifecycleThread, closeThread)
            assertEquals(DiagnosticSessionSlotState.CLOSING, scheduler.currentSlotState())
            allowCloseToFinish.countDown()
            assertTrue(completion.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.closeCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun onlyLatestAvailabilityWaiterIsRetained() {
        val closeTasks = QueueExecutor()
        val scheduler = BoundedDiagnosticSessionCloseScheduler(closeTasks, DirectExecutor)
        val callbacks = mutableListOf<String>()

        assertTrue(scheduler.tryAcquireSessionSlot())
        scheduler.notifyWhenAvailable { callbacks += "old" }
        scheduler.notifyWhenAvailable { callbacks += "latest" }
        scheduler.scheduleClose(CloseOnlySession()) {}
        closeTasks.runNext()

        assertEquals(listOf("latest"), callbacks)
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private class QueueExecutor : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() = tasks.removeAt(0).run()
    }

    private open class CloseOnlySession : DiagnosticSessionPort {
        var closeCalls = 0
        override fun resume() = Unit
        override fun setCameraTextureName(textureId: Int) = Unit
        override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) = Unit
        override fun update(): DiagnosticFrameScalars = error("not used")
        override fun pause() = Unit
        override fun close() {
            closeCalls += 1
        }
    }
}
