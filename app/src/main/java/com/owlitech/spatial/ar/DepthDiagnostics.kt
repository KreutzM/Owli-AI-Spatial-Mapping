package com.owlitech.spatial.ar

import java.nio.ByteBuffer
import kotlin.math.max

/** Repository-owned diagnostic mode names; no ARCore enum escapes the adapter. */
enum class DiagnosticDepthMode {
    NONE,
    RAW_DEPTH_ONLY,
    AUTOMATIC,
}

enum class DepthConfigurationStatus {
    UNSUPPORTED,
    CONFIGURED,
    CONFIGURATION_FAILED,
}

data class DepthConfigurationDiagnostic(
    val rawDepthOnlySupported: Boolean = false,
    val automaticSupported: Boolean = false,
    val selectedMode: DiagnosticDepthMode = DiagnosticDepthMode.NONE,
    val configuredMode: DiagnosticDepthMode = DiagnosticDepthMode.NONE,
    val status: DepthConfigurationStatus = DepthConfigurationStatus.UNSUPPORTED,
    val detail: String? = null,
)

internal fun selectDiagnosticDepthMode(
    rawDepthOnlySupported: Boolean,
    automaticSupported: Boolean,
): DiagnosticDepthMode = when {
    rawDepthOnlySupported -> DiagnosticDepthMode.RAW_DEPTH_ONLY
    automaticSupported -> DiagnosticDepthMode.AUTOMATIC
    else -> DiagnosticDepthMode.NONE
}

enum class DepthAcquisitionStatus {
    UNSUPPORTED,
    CONFIGURED_WAITING_FOR_DATA,
    NEW_DEPTH_DATA,
    REPROJECTED_DEPTH_DATA,
    NOT_YET_AVAILABLE,
    NOT_TRACKING,
    ILLEGAL_STATE,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INVALID_IMAGE_LAYOUT,
    UNEXPECTED_RUNTIME_ERROR,
}

enum class DepthDataKind {
    NEW,
    REPROJECTED,
}

enum class DepthFormatClassification {
    D_16,
    Y8,
    OTHER,
}

data class DiagnosticCameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
    val aspectRatio: Double get() = width.toDouble() / height.toDouble()
    val normalizedFx: Double get() = fx / width
    val normalizedFy: Double get() = fy / height
    val normalizedCx: Double get() = cx / width
    val normalizedCy: Double get() = cy / height
}

data class DepthPixelStatistics(
    val totalPixelCount: Int,
    val nonZeroDepthCount: Int,
    val minNonZeroDepthMillimetres: Int?,
    val maxNonZeroDepthMillimetres: Int?,
    val nonZeroConfidenceCount: Int,
    val minConfidence: Int?,
    val maxConfidence: Int?,
    val confidenceAtLeast128Count: Int,
    val zeroDepthNonZeroConfidenceCount: Int,
    val nonZeroDepthZeroConfidenceCount: Int,
) {
    val nonZeroDepthRatio: Double get() = ratio(nonZeroDepthCount)
    val nonZeroConfidenceRatio: Double get() = ratio(nonZeroConfidenceCount)
    val confidenceAtLeast128Ratio: Double get() = ratio(confidenceAtLeast128Count)

    private fun ratio(count: Int): Double = if (totalPixelCount == 0) 0.0 else count.toDouble() / totalPixelCount
}

data class DepthDiagnosticObservation(
    val frameTimestampNanos: Long,
    val rawDepthTimestampNanos: Long,
    val confidenceTimestampNanos: Long,
    val dataKind: DepthDataKind,
    val depthWidth: Int,
    val depthHeight: Int,
    val confidenceWidth: Int,
    val confidenceHeight: Int,
    val depthRowStride: Int,
    val depthPixelStride: Int,
    val confidenceRowStride: Int,
    val confidencePixelStride: Int,
    val depthFormat: DepthFormatClassification,
    val confidenceFormat: DepthFormatClassification,
    /** Full pixel statistics are present only for a distinct NEW raw-depth timestamp. */
    val statistics: DepthPixelStatistics?,
    val cpuImageIntrinsics: DiagnosticCameraIntrinsics?,
    val gpuTextureIntrinsics: DiagnosticCameraIntrinsics?,
    val displayRotation: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    val depthAspectRatio: Double get() = depthWidth.toDouble() / depthHeight.toDouble()
}

data class DepthDiagnosticCounters(
    val acquisitionAttempts: Long = 0,
    val successes: Long = 0,
    val distinctNewDepthTimestamps: Long = 0,
    val reprojections: Long = 0,
    val transientUnavailable: Long = 0,
    val failures: Long = 0,
)

data class DepthDiagnosticState(
    val configuration: DepthConfigurationDiagnostic = DepthConfigurationDiagnostic(),
    val acquisitionStatus: DepthAcquisitionStatus = DepthAcquisitionStatus.UNSUPPORTED,
    val currentObservation: DepthDiagnosticObservation? = null,
    /** Cleared on tracking/lifecycle loss. Used only to label the latest scanned NEW sample. */
    val lastNewDataStatistics: DepthPixelStatistics? = null,
    val lastNewDataTimestampNanos: Long? = null,
    val counters: DepthDiagnosticCounters = DepthDiagnosticCounters(),
    val observedNewDepthRateHz: Double = 0.0,
    val rateWindowSampleCount: Int = 0,
    val detail: String? = null,
)

internal enum class DepthFailureReason {
    NOT_YET_AVAILABLE,
    NOT_TRACKING,
    ILLEGAL_STATE,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INVALID_IMAGE_LAYOUT,
}

internal class DepthAcquisitionException(
    val reason: DepthFailureReason,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Minimal image/plane boundary used only inside the AR adapter invocation. */
internal interface DiagnosticDepthImage : AutoCloseable {
    val width: Int
    val height: Int
    val timestampNanos: Long
    val format: Int
    fun plane(): DiagnosticDepthPlane
}

internal interface DiagnosticDepthPlane {
    val rowStride: Int
    val pixelStride: Int
    val byteCount: Int
    fun unsignedByteAt(offset: Int): Int
}

internal class ByteBufferDepthPlane(
    private val buffer: ByteBuffer,
    override val rowStride: Int,
    override val pixelStride: Int,
) : DiagnosticDepthPlane {
    private val base = buffer.position()
    override val byteCount: Int = buffer.remaining()
    override fun unsignedByteAt(offset: Int): Int = buffer.get(base + offset).toInt() and 0xff
}

internal interface RawDepthFrameSource {
    fun acquireRawDepth16Bits(): DiagnosticDepthImage
    fun acquireRawDepthConfidence(): DiagnosticDepthImage
}

internal data class DepthFrameContext(
    val frameTimestampNanos: Long,
    val tracking: Boolean,
    val cpuImageIntrinsics: DiagnosticCameraIntrinsics?,
    val gpuTextureIntrinsics: DiagnosticCameraIntrinsics?,
    val displayRotation: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

internal object DepthPlaneAnalyzer {
    fun analyze(
        depth: DiagnosticDepthImage,
        confidence: DiagnosticDepthImage,
    ): DepthPixelStatistics {
        validateImagePair(depth, confidence)
        val depthPlane = depth.plane()
        val confidencePlane = confidence.plane()
        validatePlane(depth.width, depth.height, depthPlane, bytesPerPixel = 2)
        validatePlane(confidence.width, confidence.height, confidencePlane, bytesPerPixel = 1)

        val totalPixelsLong = depth.width.toLong() * depth.height.toLong()
        if (totalPixelsLong > Int.MAX_VALUE) invalid("Image dimensions exceed bounded diagnostic count range.")
        val totalPixels = totalPixelsLong.toInt()

        var nonZeroDepth = 0
        var minDepth: Int? = null
        var maxDepth: Int? = null
        var nonZeroConfidence = 0
        var minConfidence: Int? = null
        var maxConfidence: Int? = null
        var confidenceAtLeast128 = 0
        var zeroDepthNonZeroConfidence = 0
        var nonZeroDepthZeroConfidence = 0

        for (y in 0 until depth.height) {
            for (x in 0 until depth.width) {
                val depthOffset = (y.toLong() * depthPlane.rowStride +
                    x.toLong() * depthPlane.pixelStride).toInt()
                val depthMillimetres = depthPlane.unsignedByteAt(depthOffset) or
                    (depthPlane.unsignedByteAt(depthOffset + 1) shl 8)
                val confidenceOffset = (y.toLong() * confidencePlane.rowStride +
                    x.toLong() * confidencePlane.pixelStride).toInt()
                val confidenceValue = confidencePlane.unsignedByteAt(confidenceOffset)

                if (depthMillimetres != 0) {
                    nonZeroDepth += 1
                    minDepth = minDepth?.coerceAtMost(depthMillimetres) ?: depthMillimetres
                    maxDepth = maxDepth?.coerceAtLeast(depthMillimetres) ?: depthMillimetres
                }
                minConfidence = minConfidence?.coerceAtMost(confidenceValue) ?: confidenceValue
                maxConfidence = maxConfidence?.coerceAtLeast(confidenceValue) ?: confidenceValue
                if (confidenceValue != 0) {
                    nonZeroConfidence += 1
                }
                if (confidenceValue >= 128) confidenceAtLeast128 += 1
                if (depthMillimetres == 0 && confidenceValue != 0) zeroDepthNonZeroConfidence += 1
                if (depthMillimetres != 0 && confidenceValue == 0) nonZeroDepthZeroConfidence += 1
            }
        }

        return DepthPixelStatistics(
            totalPixelCount = totalPixels,
            nonZeroDepthCount = nonZeroDepth,
            minNonZeroDepthMillimetres = minDepth,
            maxNonZeroDepthMillimetres = maxDepth,
            nonZeroConfidenceCount = nonZeroConfidence,
            minConfidence = minConfidence,
            maxConfidence = maxConfidence,
            confidenceAtLeast128Count = confidenceAtLeast128,
            zeroDepthNonZeroConfidenceCount = zeroDepthNonZeroConfidence,
            nonZeroDepthZeroConfidenceCount = nonZeroDepthZeroConfidence,
        )
    }

    fun validateMetadata(depth: DiagnosticDepthImage, confidence: DiagnosticDepthImage) {
        validateImagePair(depth, confidence)
        validatePlane(depth.width, depth.height, depth.plane(), bytesPerPixel = 2)
        validatePlane(confidence.width, confidence.height, confidence.plane(), bytesPerPixel = 1)
    }

    private fun validateImagePair(depth: DiagnosticDepthImage, confidence: DiagnosticDepthImage) {
        if (depth.width <= 0 || depth.height <= 0 || confidence.width <= 0 || confidence.height <= 0) {
            invalid("Depth/confidence dimensions must be positive.")
        }
        if (depth.width != confidence.width || depth.height != confidence.height) {
            invalid("Depth/confidence dimensions must match.")
        }
    }

    private fun validatePlane(
        width: Int,
        height: Int,
        plane: DiagnosticDepthPlane,
        bytesPerPixel: Int,
    ) {
        if (plane.rowStride <= 0 || plane.pixelStride < bytesPerPixel || plane.byteCount <= 0) {
            invalid("Invalid rowStride/pixelStride/buffer size.")
        }
        val minimumRowExtent = (width - 1).toLong() * plane.pixelStride + bytesPerPixel
        if (plane.rowStride.toLong() < minimumRowExtent) {
            invalid("rowStride is smaller than the addressed pixel extent.")
        }
        val lastOffset = (height - 1).toLong() * plane.rowStride +
            minimumRowExtent - 1L
        if (lastOffset < 0L || lastOffset >= plane.byteCount.toLong()) {
            invalid("Image plane does not contain every addressed pixel.")
        }
    }

    private fun invalid(message: String): Nothing = throw DepthAcquisitionException(
        DepthFailureReason.INVALID_IMAGE_LAYOUT,
        message,
    )
}

/**
 * Constant-memory per-Session diagnostic tracker. The rate window stores at most [rateWindowCapacity]
 * distinct raw-depth timestamps; repeated timestamps never enter the window.
 */
internal class RawDepthDiagnosticTracker(
    private val configuration: DepthConfigurationDiagnostic,
    private val rateWindowCapacity: Int = 64,
    private val rateWindowDurationNanos: Long = 5_000_000_000L,
) {
    init {
        require(rateWindowCapacity >= 2)
        require(rateWindowDurationNanos > 0)
    }

    private val newDepthTimestamps = LongArray(rateWindowCapacity)
    private var rateStart = 0
    private var rateSize = 0
    private var lastRawDepthTimestampNanos: Long? = null
    private var counters = DepthDiagnosticCounters()
    private var lastNewStatistics: DepthPixelStatistics? = null
    private var lastNewTimestamp: Long? = null

    fun initialState(): DepthDiagnosticState = DepthDiagnosticState(
        configuration = configuration,
        acquisitionStatus = when (configuration.status) {
            DepthConfigurationStatus.UNSUPPORTED -> DepthAcquisitionStatus.UNSUPPORTED
            DepthConfigurationStatus.CONFIGURED -> DepthAcquisitionStatus.CONFIGURED_WAITING_FOR_DATA
            DepthConfigurationStatus.CONFIGURATION_FAILED -> DepthAcquisitionStatus.ILLEGAL_STATE
        },
        counters = counters,
        detail = configuration.detail,
    )

    fun clearCurrent(status: DepthAcquisitionStatus): DepthDiagnosticState {
        lastNewStatistics = null
        lastNewTimestamp = null
        return state(status = status, current = null)
    }

    fun observe(context: DepthFrameContext, source: RawDepthFrameSource?): DepthDiagnosticState {
        if (configuration.status == DepthConfigurationStatus.UNSUPPORTED) {
            return clearCurrent(DepthAcquisitionStatus.UNSUPPORTED)
        }
        if (configuration.status == DepthConfigurationStatus.CONFIGURATION_FAILED) {
            return state(DepthAcquisitionStatus.ILLEGAL_STATE, null, configuration.detail)
        }
        if (!context.tracking) {
            return clearCurrent(DepthAcquisitionStatus.NOT_TRACKING)
        }
        if (source == null) {
            return failureState(DepthAcquisitionStatus.ILLEGAL_STATE, "Depth frame source missing.")
        }

        counters = counters.copy(acquisitionAttempts = counters.acquisitionAttempts + 1)
        var depth: DiagnosticDepthImage? = null
        var confidence: DiagnosticDepthImage? = null
        try {
            depth = source.acquireRawDepth16Bits()
            try {
                confidence = source.acquireRawDepthConfidence()
                val depthImage = requireNotNull(depth)
                val confidenceImage = requireNotNull(confidence)
                val depthFormat = classifyDepthFormat(depthImage.format)
                val confidenceFormat = classifyConfidenceFormat(confidenceImage.format)
                val repeated = lastRawDepthTimestampNanos == depthImage.timestampNanos

                if (repeated) {
                    DepthPlaneAnalyzer.validateMetadata(depthImage, confidenceImage)
                    counters = counters.copy(
                        successes = counters.successes + 1,
                        reprojections = counters.reprojections + 1,
                    )
                    val observation = observation(
                        context = context,
                        depth = depthImage,
                        confidence = confidenceImage,
                        kind = DepthDataKind.REPROJECTED,
                        depthFormat = depthFormat,
                        confidenceFormat = confidenceFormat,
                        statistics = null,
                    )
                    return state(DepthAcquisitionStatus.REPROJECTED_DEPTH_DATA, observation)
                }

                val statistics = DepthPlaneAnalyzer.analyze(
                    depth = depthImage,
                    confidence = confidenceImage,
                )
                lastRawDepthTimestampNanos = depthImage.timestampNanos
                lastNewStatistics = statistics
                lastNewTimestamp = depthImage.timestampNanos
                addDistinctTimestamp(depthImage.timestampNanos)
                counters = counters.copy(
                    successes = counters.successes + 1,
                    distinctNewDepthTimestamps = counters.distinctNewDepthTimestamps + 1,
                )
                val observation = observation(
                    context = context,
                    depth = depthImage,
                    confidence = confidenceImage,
                    kind = DepthDataKind.NEW,
                    depthFormat = depthFormat,
                    confidenceFormat = confidenceFormat,
                    statistics = statistics,
                )
                return state(DepthAcquisitionStatus.NEW_DEPTH_DATA, observation)
            } finally {
                confidence?.close()
            }
        } catch (error: DepthAcquisitionException) {
            return when (error.reason) {
                DepthFailureReason.NOT_YET_AVAILABLE -> {
                    counters = counters.copy(transientUnavailable = counters.transientUnavailable + 1)
                    state(DepthAcquisitionStatus.NOT_YET_AVAILABLE, null, error.message)
                }
                DepthFailureReason.NOT_TRACKING -> clearCurrent(DepthAcquisitionStatus.NOT_TRACKING)
                DepthFailureReason.ILLEGAL_STATE -> failureState(DepthAcquisitionStatus.ILLEGAL_STATE, error.message)
                DepthFailureReason.DEADLINE_EXCEEDED -> failureState(DepthAcquisitionStatus.DEADLINE_EXCEEDED, error.message)
                DepthFailureReason.RESOURCE_EXHAUSTED -> failureState(DepthAcquisitionStatus.RESOURCE_EXHAUSTED, error.message)
                DepthFailureReason.INVALID_IMAGE_LAYOUT -> failureState(DepthAcquisitionStatus.INVALID_IMAGE_LAYOUT, error.message)
            }
        } catch (error: RuntimeException) {
            return failureState(DepthAcquisitionStatus.UNEXPECTED_RUNTIME_ERROR, error.message)
        } finally {
            depth?.close()
        }
    }

    fun rateWindowSizeForTest(): Int = rateSize

    private fun failureState(status: DepthAcquisitionStatus, detail: String?): DepthDiagnosticState {
        counters = counters.copy(failures = counters.failures + 1)
        lastNewStatistics = null
        lastNewTimestamp = null
        return state(status, null, detail)
    }

    private fun observation(
        context: DepthFrameContext,
        depth: DiagnosticDepthImage,
        confidence: DiagnosticDepthImage,
        kind: DepthDataKind,
        depthFormat: DepthFormatClassification,
        confidenceFormat: DepthFormatClassification,
        statistics: DepthPixelStatistics?,
    ): DepthDiagnosticObservation {
        val depthPlane = depth.plane()
        val confidencePlane = confidence.plane()
        return DepthDiagnosticObservation(
            frameTimestampNanos = context.frameTimestampNanos,
            rawDepthTimestampNanos = depth.timestampNanos,
            confidenceTimestampNanos = confidence.timestampNanos,
            dataKind = kind,
            depthWidth = depth.width,
            depthHeight = depth.height,
            confidenceWidth = confidence.width,
            confidenceHeight = confidence.height,
            depthRowStride = depthPlane.rowStride,
            depthPixelStride = depthPlane.pixelStride,
            confidenceRowStride = confidencePlane.rowStride,
            confidencePixelStride = confidencePlane.pixelStride,
            depthFormat = depthFormat,
            confidenceFormat = confidenceFormat,
            statistics = statistics,
            cpuImageIntrinsics = context.cpuImageIntrinsics,
            gpuTextureIntrinsics = context.gpuTextureIntrinsics,
            displayRotation = context.displayRotation,
            viewportWidth = context.viewportWidth,
            viewportHeight = context.viewportHeight,
        )
    }

    private fun state(
        status: DepthAcquisitionStatus,
        current: DepthDiagnosticObservation?,
        detail: String? = null,
    ): DepthDiagnosticState {
        evictOldRateSamples(current?.rawDepthTimestampNanos ?: lastNewTimestamp ?: 0L)
        return DepthDiagnosticState(
            configuration = configuration,
            acquisitionStatus = status,
            currentObservation = current,
            lastNewDataStatistics = lastNewStatistics,
            lastNewDataTimestampNanos = lastNewTimestamp,
            counters = counters,
            observedNewDepthRateHz = calculateRateHz(),
            rateWindowSampleCount = rateSize,
            detail = detail?.take(160),
        )
    }

    private fun addDistinctTimestamp(timestampNanos: Long) {
        evictOldRateSamples(timestampNanos)
        if (rateSize == rateWindowCapacity) {
            rateStart = (rateStart + 1) % rateWindowCapacity
            rateSize -= 1
        }
        newDepthTimestamps[(rateStart + rateSize) % rateWindowCapacity] = timestampNanos
        rateSize += 1
    }

    private fun evictOldRateSamples(referenceTimestampNanos: Long) {
        if (referenceTimestampNanos <= 0L) return
        val cutoff = referenceTimestampNanos - rateWindowDurationNanos
        while (rateSize > 0 && newDepthTimestamps[rateStart] < cutoff) {
            rateStart = (rateStart + 1) % rateWindowCapacity
            rateSize -= 1
        }
    }

    private fun calculateRateHz(): Double {
        if (rateSize < 2) return 0.0
        val first = newDepthTimestamps[rateStart]
        val last = newDepthTimestamps[(rateStart + rateSize - 1) % rateWindowCapacity]
        val duration = max(1L, last - first)
        return (rateSize - 1).toDouble() * 1_000_000_000.0 / duration.toDouble()
    }

    private fun classifyDepthFormat(format: Int): DepthFormatClassification =
        if (format == android.hardware.HardwareBuffer.D_16) DepthFormatClassification.D_16
        else DepthFormatClassification.OTHER

    private fun classifyConfidenceFormat(format: Int): DepthFormatClassification =
        if (format == android.graphics.ImageFormat.Y8 || format == com.google.ar.core.ImageFormat.Y8) {
            DepthFormatClassification.Y8
        } else {
            DepthFormatClassification.OTHER
        }
}
