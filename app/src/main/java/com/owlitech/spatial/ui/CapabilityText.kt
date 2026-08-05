package com.owlitech.spatial.ui

import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability

fun capabilityTextResource(capability: ArCapability): Int = when (capability) {
    ArCapability.Checking -> R.string.ar_checking
    ArCapability.SupportedInstalled -> R.string.ar_supported_installed
    ArCapability.SupportedNotInstalled -> R.string.ar_supported_not_installed
    ArCapability.Unsupported -> R.string.ar_unsupported
    ArCapability.Transient -> R.string.ar_transient
    is ArCapability.Error -> R.string.ar_error
}
