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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.owlitech.spatial.ar.ArInstallAttemptState
import com.owlitech.spatial.ar.ArInstallController
import com.owlitech.spatial.ar.CameraPermissionAction
import com.owlitech.spatial.ar.CameraPermissionRequestOutcome
import com.owlitech.spatial.ar.CameraPermissionRequestRecord
import com.owlitech.spatial.ar.CameraPermissionTracker
import com.owlitech.spatial.ar.DiagnosticGlSurfaceView
import com.owlitech.spatial.ar.DiagnosticLifecycleCoordinator
import com.owlitech.spatial.ar.DiagnosticObservation
import com.owlitech.spatial.ar.LatestValueDispatcher
import com.owlitech.spatial.ar.ProcessDiagnosticSessionCloseScheduler
import com.owlitech.spatial.ar.SessionLifecycleState
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
    private lateinit var permissionTracker: CameraPermissionTracker
    private lateinit var sessionController: ArDiagnosticSessionController
    private lateinit var diagnosticSurfaceView: DiagnosticGlSurfaceView
    private lateinit var lifecycleCoordinator: DiagnosticLifecycleCoordinator
    private lateinit var sessionUpdateDispatcher: LatestValueDispatcher<SessionUpdate>

    private val permissionPreferences by lazy {
        getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionTracker.onRequestResult(granted)
        persistCompletedPermissionOutcome(permissionTracker.snapshot().lastCompletedOutcome)
        refreshCameraPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val restoredInstallAttempt = ArInstallAttemptState(
            awaitingReturnFromInstallUi = savedInstanceState?.getBoolean(
                STATE_INSTALL_ATTEMPT_PENDING,
                false,
            ) == true,
        )
        diagnosticState = ArDiagnosticState(
            capability = if (restoredInstallAttempt.awaitingReturnFromInstallUi) {
                ArCapability.InstallationRequested
            } else {
                ArCapability.Checking
            },
        )

        capabilityProbe = ArCoreCapabilityProbe(applicationContext)
        installController = ArInstallController(
            installPort = ArCoreInstallAdapter(this),
            initialState = restoredInstallAttempt,
        )
        permissionTracker = CameraPermissionTracker(
            CameraPermissionRequestRecord(
                lastCompletedOutcome = restoredCompletedPermissionOutcome(),
            ),
        )
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
            sessionCloseScheduler = ProcessDiagnosticSessionCloseScheduler,
            onRuntimeReleaseRequested = { operation, error ->
                mainHandler.post {
                    if (!destroyed) {
                        lifecycleCoordinator.onRuntimeFailure(operation, error)
                    }
                }
            },
            onSessionSlotAvailable = {
                mainHandler.post {
                    if (!destroyed) lifecycleCoordinator.onSessionSlotAvailable()
                }
            },
            onStateChanged = { lifecycle, observation ->
                sessionUpdateDispatcher.offer(SessionUpdate(lifecycle, observation))
            },
        )
        diagnosticSurfaceView = DiagnosticGlSurfaceView(this).also {
            it.attachController(sessionController)
            // A renderer starts a GL thread immediately. Keep it paused until Session.resume()
            // succeeds; the coordinator also pauses it before every Session pause/release.
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
        if (!restoredInstallAttempt.awaitingReturnFromInstallUi) {
            checkCapability()
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_INSTALL_ATTEMPT_PENDING,
            installController.snapshot().awaitingReturnFromInstallUi,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        // This only revokes ownership, pauses if needed, and schedules native close. It never waits
        // for Session.close(), and the process-wide close worker outlives this Activity instance.
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
                permissionTracker.onRequestLaunched()
                refreshCameraPermissionState()
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
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permissionTracker.observeGrantedPermission()
            persistCompletedPermissionOutcome(CameraPermissionRequestOutcome.GRANTED)
        }
        val state = permissionTracker.currentState(
            granted = granted,
            shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA,
            ),
        )
        if (state != diagnosticState.cameraPermission) {
            diagnosticState = diagnosticState.copy(cameraPermission = state)
        }
        updateSessionPrerequisites()
    }

    private fun restoredCompletedPermissionOutcome(): CameraPermissionRequestOutcome {
        val stored = permissionPreferences.getString(KEY_PERMISSION_COMPLETED_OUTCOME, null)
        return CameraPermissionRequestOutcome.entries.firstOrNull { it.name == stored }
            ?: CameraPermissionRequestOutcome.NONE
    }

    private fun persistCompletedPermissionOutcome(outcome: CameraPermissionRequestOutcome) {
        permissionPreferences.edit()
            .putString(KEY_PERMISSION_COMPLETED_OUTCOME, outcome.name)
            .apply()
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
        const val PERMISSION_PREFERENCES = "camera_permission_state"
        const val KEY_PERMISSION_COMPLETED_OUTCOME = "camera_permission_completed_outcome"
        const val STATE_INSTALL_ATTEMPT_PENDING = "arcore_install_attempt_pending"
    }
}
