package com.owlitech.spatial.ar

sealed interface ArCapability {
    data object Checking : ArCapability
    data object SupportedInstalled : ArCapability
    data object SupportedNotInstalled : ArCapability
    data object Unsupported : ArCapability
    data object Transient : ArCapability
    data class Error(val reason: String) : ArCapability
}

fun interface ArCapabilityProbe {
    fun check(): ArCapability
}
