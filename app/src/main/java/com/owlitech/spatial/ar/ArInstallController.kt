package com.owlitech.spatial.ar

data class ArInstallAttemptState(
    val awaitingReturnFromInstallUi: Boolean = false,
)

class ArInstallController(
    private val installPort: ArInstallPort,
    initialState: ArInstallAttemptState = ArInstallAttemptState(),
) {
    private var state = initialState

    @Synchronized
    fun requestFromUser(): ArCapability {
        if (state.awaitingReturnFromInstallUi) return ArCapability.InstallationRequested
        return request(userRequestedInstall = true)
    }

    /**
     * Completes exactly one pending install attempt after the Activity returns. The pending bit is
     * cleared before `requestInstall(..., false)` so repeated resume or recreation cannot loop it.
     */
    @Synchronized
    fun onActivityResumed(): ArCapability? {
        if (!state.awaitingReturnFromInstallUi) return null
        state = ArInstallAttemptState()
        return request(userRequestedInstall = false)
    }

    @Synchronized
    fun snapshot(): ArInstallAttemptState = state

    @Synchronized
    fun hasPendingInstallAttempt(): Boolean = state.awaitingReturnFromInstallUi

    private fun request(userRequestedInstall: Boolean): ArCapability = try {
        when (installPort.requestInstall(userRequestedInstall)) {
            InstallRequestResult.INSTALLED -> ArCapability.SupportedInstalled
            InstallRequestResult.INSTALL_REQUESTED -> {
                if (userRequestedInstall) {
                    state = ArInstallAttemptState(awaitingReturnFromInstallUi = true)
                }
                ArCapability.InstallationRequested
            }
        }
    } catch (error: ArInstallException) {
        ArCapability.Unavailable(error.reason)
    } catch (_: RuntimeException) {
        ArCapability.Unavailable(ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR)
    }
}
