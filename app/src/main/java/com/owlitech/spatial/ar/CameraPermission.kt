package com.owlitech.spatial.ar

/**
 * Tracks actual permission-request completion separately from request launch history.
 *
 * A false rationale result is interpreted as permanent denial only after this app has received a
 * completed denied result. Revocation/auto-reset after an observed grant remains requestable.
 */
class CameraPermissionTracker(
    initialRecord: CameraPermissionRequestRecord = CameraPermissionRequestRecord(),
) {
    private var record = initialRecord

    @Synchronized
    fun onRequestLaunched(): CameraPermissionRequestRecord {
        record = record.copy(requestInFlight = true)
        return record
    }

    @Synchronized
    fun onRequestResult(granted: Boolean): CameraPermissionRequestRecord {
        record = CameraPermissionRequestRecord(
            requestInFlight = false,
            lastCompletedOutcome = if (granted) {
                CameraPermissionRequestOutcome.GRANTED
            } else {
                CameraPermissionRequestOutcome.DENIED
            },
        )
        return record
    }

    @Synchronized
    fun observeGrantedPermission(): CameraPermissionRequestRecord {
        record = CameraPermissionRequestRecord(
            requestInFlight = false,
            lastCompletedOutcome = CameraPermissionRequestOutcome.GRANTED,
        )
        return record
    }

    @Synchronized
    fun currentState(
        granted: Boolean,
        shouldShowRequestPermissionRationale: Boolean,
    ): CameraPermissionState = cameraPermissionState(
        granted = granted,
        requestRecord = record,
        shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale,
    )

    @Synchronized
    fun snapshot(): CameraPermissionRequestRecord = record
}

fun cameraPermissionState(
    granted: Boolean,
    requestRecord: CameraPermissionRequestRecord,
    shouldShowRequestPermissionRationale: Boolean,
): CameraPermissionState = when {
    granted -> CameraPermissionState.GRANTED
    requestRecord.requestInFlight -> CameraPermissionState.REQUEST_IN_FLIGHT
    requestRecord.lastCompletedOutcome == CameraPermissionRequestOutcome.DENIED &&
        shouldShowRequestPermissionRationale -> CameraPermissionState.DENIED_CAN_ASK_AGAIN
    requestRecord.lastCompletedOutcome == CameraPermissionRequestOutcome.DENIED ->
        CameraPermissionState.DENIED_PERMANENTLY
    requestRecord.lastCompletedOutcome == CameraPermissionRequestOutcome.GRANTED ->
        CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE
    else -> CameraPermissionState.NOT_REQUESTED
}
