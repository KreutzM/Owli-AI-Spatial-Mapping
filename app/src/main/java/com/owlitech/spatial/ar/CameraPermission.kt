package com.owlitech.spatial.ar

fun cameraPermissionState(
    granted: Boolean,
    requestWasMade: Boolean,
    shouldShowRequestPermissionRationale: Boolean,
): CameraPermissionState = when {
    granted -> CameraPermissionState.GRANTED
    !requestWasMade -> CameraPermissionState.NOT_REQUESTED
    shouldShowRequestPermissionRationale -> CameraPermissionState.DENIED_CAN_ASK_AGAIN
    else -> CameraPermissionState.DENIED_PERMANENTLY
}
