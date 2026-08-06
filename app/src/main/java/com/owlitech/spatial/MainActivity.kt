package com.owlitech.spatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArCoreCapabilityProbe
import com.owlitech.spatial.ar.ArCoreDiagnosticSessionFactory
import com.owlitech.spatial.ar.ArCoreInstallAdapter
import com.owlitech.spatial.ar.ArDiagnosticSessionController
import com.owlitech.spatial.ar.ArDiagnosticState
import com.owlitech.spatial.ar.ArInstallController
import com.owlitech.spatial.ar.CameraPermissionAction
import com.owlitech.spatial.ar.DiagnosticGlSurfaceView
import com.owlitech.spatial.ar.DiagnosticLifecycleCoordinator
import com.owlitech.spatial.ar.DiagnosticObservation
import com.owlitech.spatial.ar.LatestValueDispatcher
import com.owlitech.spatial.ar.SessionLifecycleState
import com.owlitech.spatial.ar.cameraPermissionState
import com.owlitech.spatial.ar.sessionPrerequisitesSatisfied
import com.owlitech.spatial.ui.BootstrapScreen
import com.owlitech.spatial.ui.DiagnosticTestTags
import com.owlitech.spatial.ui.OwliSpatialTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private data class SessionUpdate(
        val lifecycle: SessionLifecycleState,
        val observation: DiagnosticObservation?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val capabilityExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcore-capability-probe").apply { isDaemon = true }
    }
    private val capabilityCheckRunning = AtomicBoolean(false)
    private var destroyed = false

    private var diagnosticState by mutableStateOf(ArDiagnosticState())
    private lateinit var capabilityProbe: ArCoreCapabilityProbe
    private lateinit var installController: ArInstallController
    private lateinit var sessionController: ArDiagnosticSessionController
    private lateinit var diagnosticSurfaceView: DiagnosticGlSurfaceView
    private lateinit var lifecycleCoordinator: DiagnosticLifecycleCoordinator
    private lateinit var sessionUpdateDispatcher: LatestValueDispatcher<SessionUpdate>

    private val permissionPreferences by lazy {
        getSharedPreferences("camera_permission_state", MODE_PRIVATE)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshCameraPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        capabilityProbe = ArCoreCapabilityProbe(applicationContext)
        installController = ArInstallController(ArCoreInstallAdapter(this))
        sessionUpdateDispatcher = LatestValueDispatcher(
            post = { block -> mainHandler.post(block) },
            consume = { update ->
                if (!destroyed) {
                    diagnosticState = diagnosticState.copy(
                        sessionLifecycle = update.lifecycle,
                        observation = update.observation,
                    )
                }
            },
        )
        sessionController = ArDiagnosticSessionController(
            sessionFactory = ArCoreDiagnosticSessionFactory(applicationContext),
            onStateChanged = { lifecycle, observation ->
                sessionUpdateDispatcher.offer(SessionUpdate(lifecycle, observation))
            },
        )
        diagnosticSurfaceView = DiagnosticGlSurfaceView(this).also {
            it.attachController(sessionController)
            // GLSurfaceView starts its render thread when a renderer is attached. Keep it paused
            // until the coordinator has successfully resumed an eligible ARCore Session.
            it.pauseSurface()
        }
        lifecycleCoordinator = DiagnosticLifecycleCoordinator(
            sessionController = sessionController,
            surfacePort = diagnosticSurfaceView,
        )

        refreshCameraPermissionState()
        setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = diagnosticState,
                    onCheckAgain = ::checkCapability,
                    onInstallArCore = ::requestArCoreInstallation,
                    onPermissionAction = ::handlePermissionAction,
                    diagnosticSurface = {
                        AndroidView(
                            factory = { diagnosticSurfaceView },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .testTag(DiagnosticTestTags.GL_SURFACE),
                        )
                    },
                )
            }
        }
        checkCapability()
    }

    override fun onResume() {
        super.onResume()
        refreshCameraPermissionState()
        val installFollowUp = installController.onActivityResumed()
        when {
            installFollowUp != null -> setCapability(installFollowUp)
            diagnosticState.capability == ArCapability.InstallationRequested -> checkCapability()
        }
        lifecycleCoordinator.onActivityResume()
    }

    override fun onPause() {
        lifecycleCoordinator.onActivityPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        lifecycleCoordinator.close()
        capabilityExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun checkCapability() {
        if (!capabilityCheckRunning.compareAndSet(false, true)) return
        setCapability(ArCapability.Checking)
        capabilityExecutor.execute {
            var result = capabilityProbe.check()
            var retryCount = 0
            while (
                result is ArCapability.Transient &&
                retryCount < 8 &&
                !Thread.currentThread().isInterrupted
            ) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                result = capabilityProbe.check()
                retryCount += 1
            }
            mainHandler.post {
                capabilityCheckRunning.set(false)
                if (!destroyed) setCapability(result)
            }
        }
    }

    private fun requestArCoreInstallation() {
        setCapability(installController.requestFromUser())
    }

    private fun handlePermissionAction(action: CameraPermissionAction) {
        when (action) {
            CameraPermissionAction.REQUEST,
            CameraPermissionAction.RETRY,
            -> {
                permissionPreferences.edit().putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, true).apply()
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            CameraPermissionAction.OPEN_APPLICATION_SETTINGS -> {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            CameraPermissionAction.NONE -> Unit
        }
    }

    private fun refreshCameraPermissionState() {
        val state = cameraPermissionState(
            granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
            requestWasMade = permissionPreferences.getBoolean(
                KEY_CAMERA_PERMISSION_REQUESTED,
                false,
            ),
            shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA,
            ),
        )
        if (state != diagnosticState.cameraPermission) {
            diagnosticState = diagnosticState.copy(cameraPermission = state)
        }
        updateSessionPrerequisites()
    }

    private fun setCapability(capability: ArCapability) {
        if (capability != diagnosticState.capability) {
            diagnosticState = diagnosticState.copy(capability = capability)
        }
        updateSessionPrerequisites()
    }

    private fun updateSessionPrerequisites() {
        lifecycleCoordinator.onPrerequisitesChanged(
            sessionPrerequisitesSatisfied(
                diagnosticState.capability,
                diagnosticState.cameraPermission,
            ),
        )
    }

    private companion object {
        const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested"
    }
}
