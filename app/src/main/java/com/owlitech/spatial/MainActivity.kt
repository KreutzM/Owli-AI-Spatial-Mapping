package com.owlitech.spatial

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArCoreCapabilityProbe
import com.owlitech.spatial.ui.BootstrapScreen
import com.owlitech.spatial.ui.OwliSpatialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OwliSpatialTheme {
                val probe = remember { ArCoreCapabilityProbe(applicationContext) }
                var capability by remember { mutableStateOf<ArCapability>(ArCapability.Checking) }
                var checkGeneration by remember { mutableStateOf(0) }
                var cameraGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED,
                    )
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> cameraGranted = granted }

                LaunchedEffect(checkGeneration) {
                    capability = ArCapability.Checking
                    capability = withContext(Dispatchers.Default) {
                        var current = probe.check()
                        repeat(8) {
                            if (current !is ArCapability.Transient) return@withContext current
                            delay(250)
                            current = probe.check()
                        }
                        current
                    }
                }

                BootstrapScreen(
                    capability = capability,
                    cameraPermissionGranted = cameraGranted,
                    onCheckAgain = { checkGeneration += 1 },
                    onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }
    }
}
