package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArInstallControllerTest {
    @Test
    fun installDialogRequiresUserActionAndIsNotLoopedOnRepeatedResume() {
        val port = FakeInstallPort(
            mutableListOf(
                InstallRequestResult.INSTALL_REQUESTED,
                InstallRequestResult.INSTALLED,
            ),
        )
        val controller = ArInstallController(port)

        assertNull(controller.onActivityResumed())
        assertEquals(ArCapability.InstallationRequested, controller.requestFromUser())
        assertEquals(ArCapability.InstallationRequested, controller.requestFromUser())
        assertTrue(controller.hasPendingInstallAttempt())
        assertEquals(listOf(true), port.requests)

        assertEquals(ArCapability.SupportedInstalled, controller.onActivityResumed())
        assertFalse(controller.hasPendingInstallAttempt())
        assertNull(controller.onActivityResumed())
        assertEquals(listOf(true, false), port.requests)
    }

    @Test
    fun pendingAttemptSurvivesControllerRecreationAndRunsOneFalseFollowUp() {
        val port = FakeInstallPort(
            mutableListOf(
                InstallRequestResult.INSTALL_REQUESTED,
                InstallRequestResult.INSTALLED,
            ),
        )
        val firstController = ArInstallController(port)
        assertEquals(ArCapability.InstallationRequested, firstController.requestFromUser())

        val recreatedController = ArInstallController(
            installPort = port,
            initialState = firstController.snapshot(),
        )
        assertTrue(recreatedController.hasPendingInstallAttempt())
        assertEquals(ArCapability.SupportedInstalled, recreatedController.onActivityResumed())
        assertNull(recreatedController.onActivityResumed())
        assertEquals(listOf(true, false), port.requests)
    }

    @Test
    fun falseFollowUpCannotRearmPendingAttemptOrLoop() {
        val port = FakeInstallPort(
            mutableListOf(
                InstallRequestResult.INSTALL_REQUESTED,
                InstallRequestResult.INSTALL_REQUESTED,
            ),
        )
        val first = ArInstallController(port)
        first.requestFromUser()
        val recreated = ArInstallController(port, first.snapshot())

        assertEquals(ArCapability.InstallationRequested, recreated.onActivityResumed())
        assertFalse(recreated.hasPendingInstallAttempt())
        assertNull(recreated.onActivityResumed())
        assertEquals(listOf(true, false), port.requests)
    }

    @Test
    fun knownInstallFailuresMapToPreciseUnavailableStates() {
        ArUnavailableReason.entries.forEach { reason ->
            val controller = ArInstallController(
                object : ArInstallPort {
                    override fun requestInstall(userRequestedInstall: Boolean): InstallRequestResult {
                        throw ArInstallException(reason)
                    }
                },
            )
            assertEquals(ArCapability.Unavailable(reason), controller.requestFromUser())
        }
    }

    private class FakeInstallPort(
        private val results: MutableList<InstallRequestResult>,
    ) : ArInstallPort {
        val requests = mutableListOf<Boolean>()

        override fun requestInstall(userRequestedInstall: Boolean): InstallRequestResult {
            requests += userRequestedInstall
            return results.removeAt(0)
        }
    }
}
