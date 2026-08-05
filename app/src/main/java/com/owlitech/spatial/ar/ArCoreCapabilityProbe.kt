package com.owlitech.spatial.ar

import android.content.Context
import com.google.ar.core.ArCoreApk

class ArCoreCapabilityProbe(context: Context) : ArCapabilityProbe {
    private val applicationContext = context.applicationContext

    override fun check(): ArCapability = runCatching {
        val availability = ArCoreApk.getInstance().checkAvailability(applicationContext)
        when {
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCapability.SupportedInstalled
            availability.isTransient -> ArCapability.Transient
            availability.isSupported -> ArCapability.SupportedNotInstalled
            else -> ArCapability.Unsupported
        }
    }.getOrElse { error ->
        ArCapability.Error(error.message ?: error::class.java.simpleName)
    }
}
