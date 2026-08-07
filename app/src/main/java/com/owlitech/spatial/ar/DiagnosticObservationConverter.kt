package com.owlitech.spatial.ar

class DiagnosticObservationConverter {
    fun convert(frame: DiagnosticFrameScalars): DiagnosticObservation {
        val tracking = frame.trackingState == DiagnosticTrackingState.TRACKING
        return DiagnosticObservation(
            frameTimestampNanos = frame.frameTimestampNanos,
            trackingState = frame.trackingState,
            trackingFailureReason = frame.trackingFailureReason,
            worldFromArCoreCamera = if (tracking) frame.toWorldFromCameraOrNull() else null,
            nativeImageIntrinsics = if (tracking) frame.validIntrinsicsOrNull() else null,
        )
    }

    private fun DiagnosticFrameScalars.validIntrinsicsOrNull(): NativeImageIntrinsics? {
        val fx = imageFx ?: return null
        val fy = imageFy ?: return null
        val cx = imageCx ?: return null
        val cy = imageCy ?: return null
        val width = imageWidth ?: return null
        val height = imageHeight ?: return null
        if (!fx.isFinite() || fx <= 0.0 || !fy.isFinite() || fy <= 0.0) return null
        if (!cx.isFinite() || !cy.isFinite()) return null
        if (width <= 0 || height <= 0) return null
        return NativeImageIntrinsics(
            fx = fx,
            fy = fy,
            cx = cx,
            cy = cy,
            width = width,
            height = height,
        )
    }
}
