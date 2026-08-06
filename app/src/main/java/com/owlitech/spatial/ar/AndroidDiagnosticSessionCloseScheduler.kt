package com.owlitech.spatial.ar

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-lifetime close worker; it is deliberately not tied to an Activity's destruction. */
object ProcessDiagnosticSessionCloseScheduler : DiagnosticSessionCloseScheduler by
    BoundedDiagnosticSessionCloseScheduler(
        closeExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable ->
                Thread(runnable, "arcore-session-close").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ),
        callbackExecutor = MainThreadExecutor,
    )

private object MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        handler.post(command)
    }
}
