package com.owlitech.spatial.ar

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A one-worker, one-queued-task close scheduler with a single latest idle waiter.
 *
 * The state gate also spans Activity recreation: a new controller cannot acquire an ARCore Session
 * slot while the previous controller still owns a Session or its native close is in flight.
 */
class BoundedDiagnosticSessionCloseScheduler(
    private val closeExecutor: Executor,
    private val callbackExecutor: Executor,
) : DiagnosticSessionCloseScheduler {
    private val lock = Any()
    private var slotState = DiagnosticSessionSlotState.AVAILABLE
    private var latestIdleWaiter: (() -> Unit)? = null

    override fun tryAcquireSessionSlot(): Boolean = synchronized(lock) {
        if (slotState != DiagnosticSessionSlotState.AVAILABLE) return@synchronized false
        slotState = DiagnosticSessionSlotState.OWNED
        true
    }

    override fun releaseSessionSlot() {
        val waiter = synchronized(lock) {
            check(slotState == DiagnosticSessionSlotState.OWNED) {
                "Only an acquired Session slot can be released without closing."
            }
            slotState = DiagnosticSessionSlotState.AVAILABLE
            latestIdleWaiter.also { latestIdleWaiter = null }
        }
        waiter?.let { callbackExecutor.execute(it) }
    }

    override fun scheduleClose(
        session: DiagnosticSessionPort,
        onComplete: (Throwable?) -> Unit,
    ) {
        synchronized(lock) {
            check(slotState == DiagnosticSessionSlotState.OWNED) {
                "Exactly one owned Session is required before scheduling close."
            }
            slotState = DiagnosticSessionSlotState.CLOSING
        }

        try {
            closeExecutor.execute {
                val closeError = try {
                    session.close()
                    null
                } catch (error: Throwable) {
                    error
                }
                val waiter = synchronized(lock) {
                    slotState = DiagnosticSessionSlotState.AVAILABLE
                    latestIdleWaiter.also { latestIdleWaiter = null }
                }
                callbackExecutor.execute {
                    try {
                        onComplete(closeError)
                    } finally {
                        waiter?.invoke()
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            // The native Session is still open. Keep the slot permanently unavailable rather than
            // permit a second Session to overlap an unclosed one.
            callbackExecutor.execute { onComplete(error) }
        }
    }

    override fun notifyWhenAvailable(callback: () -> Unit) {
        val runImmediately = synchronized(lock) {
            if (slotState == DiagnosticSessionSlotState.AVAILABLE) {
                true
            } else {
                latestIdleWaiter = callback
                false
            }
        }
        if (runImmediately) callbackExecutor.execute(callback)
    }

    override fun currentSlotState(): DiagnosticSessionSlotState = synchronized(lock) { slotState }
}
