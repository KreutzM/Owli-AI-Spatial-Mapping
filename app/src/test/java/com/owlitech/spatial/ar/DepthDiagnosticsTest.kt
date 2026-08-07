package com.owlitech.spatial.ar

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthDiagnosticsTest {
    @Test fun unsupportedModesRemainNonFatal() {
        val state = RawDepthDiagnosticTracker(config(false, false)).observe(ctx(), null)
        assertEquals(DepthAcquisitionStatus.UNSUPPORTED, state.acquisitionStatus)
        assertEquals(0L, state.counters.failures)
    }

    @Test fun rawPreferredAndAutomaticFallbackOnlyWhenRawUnavailable() {
        assertEquals(DiagnosticDepthMode.RAW_DEPTH_ONLY, selectDiagnosticDepthMode(true, true))
        assertEquals(DiagnosticDepthMode.RAW_DEPTH_ONLY, selectDiagnosticDepthMode(true, false))
        assertEquals(DiagnosticDepthMode.AUTOMATIC, selectDiagnosticDepthMode(false, true))
        assertEquals(DiagnosticDepthMode.NONE, selectDiagnosticDepthMode(false, false))
    }

    @Test fun configurationFailureIsExplicitAndNonAcquiring() {
        val state = RawDepthDiagnosticTracker(
            DepthConfigurationDiagnostic(true, true, DiagnosticDepthMode.RAW_DEPTH_ONLY,
                status = DepthConfigurationStatus.CONFIGURATION_FAILED, detail = "configure failed"),
        ).initialState()
        assertEquals(DepthAcquisitionStatus.ILLEGAL_STATE, state.acquisitionStatus)
        assertEquals("configure failed", state.detail)
    }

    @Test fun noAcquisitionWhileNotTracking() {
        val source = FakeSource(depth(), confidence())
        assertEquals(DepthAcquisitionStatus.NOT_TRACKING, tracker().observe(ctx(false), source).acquisitionStatus)
        assertEquals(0, source.depthCalls); assertEquals(0, source.confidenceCalls)
    }

    @Test fun expectedFailuresMapDistinctly() {
        fun status(reason: DepthFailureReason) = tracker().observe(ctx(), ThrowingSource(reason)).acquisitionStatus
        assertEquals(DepthAcquisitionStatus.NOT_YET_AVAILABLE, status(DepthFailureReason.NOT_YET_AVAILABLE))
        assertEquals(DepthAcquisitionStatus.NOT_TRACKING, status(DepthFailureReason.NOT_TRACKING))
        assertEquals(DepthAcquisitionStatus.ILLEGAL_STATE, status(DepthFailureReason.ILLEGAL_STATE))
        assertEquals(DepthAcquisitionStatus.DEADLINE_EXCEEDED, status(DepthFailureReason.DEADLINE_EXCEEDED))
        assertEquals(DepthAcquisitionStatus.RESOURCE_EXHAUSTED, status(DepthFailureReason.RESOURCE_EXHAUSTED))
        val transient = tracker().observe(ctx(), ThrowingSource(DepthFailureReason.NOT_YET_AVAILABLE))
        assertEquals(1L, transient.counters.transientUnavailable); assertEquals(0L, transient.counters.failures)
    }

    @Test fun malformedAndUnexpectedFailuresMapDistinctly() {
        val malformed = FakeSource(image(2, 2, 1, HardwareBuffer.D_16, byteArrayOf(1, 0), 2, 2), confidence())
        assertEquals(DepthAcquisitionStatus.INVALID_IMAGE_LAYOUT, tracker().observe(ctx(), malformed).acquisitionStatus)
        val unexpected = object : RawDepthFrameSource {
            override fun acquireRawDepth16Bits(): DiagnosticDepthImage = error("boom")
            override fun acquireRawDepthConfidence(): DiagnosticDepthImage = error("unused")
        }
        assertEquals(DepthAcquisitionStatus.UNEXPECTED_RUNTIME_ERROR, tracker().observe(ctx(), unexpected).acquisitionStatus)
    }

    @Test fun depthClosesWhenConfidenceAcquireFails() {
        val depth = depth()
        val source = object : RawDepthFrameSource {
            override fun acquireRawDepth16Bits() = depth
            override fun acquireRawDepthConfidence(): DiagnosticDepthImage =
                throw DepthAcquisitionException(DepthFailureReason.NOT_YET_AVAILABLE)
        }
        assertEquals(DepthAcquisitionStatus.NOT_YET_AVAILABLE, tracker().observe(ctx(), source).acquisitionStatus)
        assertEquals(1, depth.closeCalls)
    }

    @Test fun confidenceFailureStillMarksRawTimestampAsSeen() {
        val tracker = tracker()
        val failedDepth = depth(50)
        val failedSource = object : RawDepthFrameSource {
            override fun acquireRawDepth16Bits() = failedDepth
            override fun acquireRawDepthConfidence(): DiagnosticDepthImage =
                throw DepthAcquisitionException(DepthFailureReason.NOT_YET_AVAILABLE)
        }

        val failed = tracker.observe(ctx(), failedSource)
        assertEquals(DepthAcquisitionStatus.NOT_YET_AVAILABLE, failed.acquisitionStatus)
        assertEquals(1, failedDepth.closeCalls)
        assertEquals(1L, failed.counters.distinctNewDepthTimestamps)
        assertEquals(0L, failed.counters.successes)

        val repeated = tracker.observe(ctx(frame = 200), FakeSource(depth(50), confidence(52)))
        assertEquals(DepthDataKind.REPROJECTED, repeated.currentObservation?.dataKind)
        assertNull(repeated.currentObservation?.statistics)
        assertEquals(1L, repeated.counters.distinctNewDepthTimestamps)
        assertEquals(1L, repeated.counters.reprojections)
        assertEquals(1L, repeated.counters.successes)
    }

    @Test fun bothImagesCloseExactlyOnceAndOnlyScalarsEscape() {
        val depth = depth(); val confidence = confidence()
        val state = tracker().observe(ctx(), FakeSource(depth, confidence))
        assertEquals(1, depth.closeCalls); assertEquals(1, confidence.closeCalls)
        assertEquals(DepthAcquisitionStatus.NEW_DEPTH_DATA, state.acquisitionStatus)
        assertFalse(DepthDiagnosticState::class.java.declaredFields.any {
            DiagnosticDepthImage::class.java.isAssignableFrom(it.type) || DiagnosticDepthPlane::class.java.isAssignableFrom(it.type)
        })
    }

    @Test fun unsignedDepthZeroUnknownAndUnsignedConfidenceParseExactly() {
        val depth = image(3, 1, 10, HardwareBuffer.D_16,
            byteArrayOf(0, 0, 0x34, 0x12, 0x40, 0x9c.toByte()), 6, 2)
        val confidence = image(3, 1, 11, ImageFormat.Y8,
            byteArrayOf(0, 128.toByte(), 255.toByte()), 3, 1)
        val s = stats(depth, confidence)
        assertEquals(2, s.nonZeroDepthCount); assertEquals(0x1234, s.minNonZeroDepthMillimetres)
        assertEquals(40000, s.maxNonZeroDepthMillimetres); assertEquals(2, s.nonZeroConfidenceCount)
        assertEquals(128, s.minConfidence); assertEquals(255, s.maxConfidence); assertEquals(2, s.confidenceAtLeast128Count)
    }

    @Test fun depthRowAndPixelStridePaddingAreRespected() {
        val depth = image(2, 2, 10, HardwareBuffer.D_16,
            byteArrayOf(1,0,99,99,2,0,88,88, 3,0,77,77,4,0,66,66), 8, 4)
        val s = stats(depth, image(2, 2, 11, ImageFormat.Y8, byteArrayOf(1,2,9,9,3,4), 4, 1))
        assertEquals(4, s.nonZeroDepthCount); assertEquals(1, s.minNonZeroDepthMillimetres); assertEquals(4, s.maxNonZeroDepthMillimetres)
    }

    @Test fun confidenceRowAndPixelStridePaddingAreRespected() {
        val confidence = image(2, 2, 11, ImageFormat.Y8,
            byteArrayOf(10,99,200.toByte(),88,77,77,30,66,255.toByte()), 6, 2)
        val s = stats(depth(), confidence)
        assertEquals(4, s.nonZeroConfidenceCount); assertEquals(10, s.minConfidence)
        assertEquals(255, s.maxConfidence); assertEquals(2, s.confidenceAtLeast128Count)
    }

    @Test fun mismatchedDimensionsAreRejected() {
        val state = tracker().observe(ctx(), FakeSource(depth(), image(1, 2, 11, ImageFormat.Y8, byteArrayOf(1,2), 1, 1)))
        assertEquals(DepthAcquisitionStatus.INVALID_IMAGE_LAYOUT, state.acquisitionStatus)
    }

    @Test fun depthConfidenceInconsistenciesAreCounted() {
        val s = stats(
            image(2, 1, 10, HardwareBuffer.D_16, byteArrayOf(0,0,9,0), 4, 2),
            image(2, 1, 11, ImageFormat.Y8, byteArrayOf(7,0), 2, 1),
        )
        assertEquals(1, s.zeroDepthNonZeroConfidenceCount); assertEquals(1, s.nonZeroDepthZeroConfidenceCount)
    }

    @Test fun repeatedTimestampIsReprojectionWithoutRescanOrDistinctIncrement() {
        val tracker = tracker()
        val first = tracker.observe(ctx(), FakeSource(depth(50), confidence(51)))
        val repeated = tracker.observe(ctx(frame = 200), FakeSource(depth(50), confidence(52)))
        assertEquals(DepthDataKind.NEW, first.currentObservation?.dataKind)
        assertEquals(DepthDataKind.REPROJECTED, repeated.currentObservation?.dataKind)
        assertNull(repeated.currentObservation?.statistics); assertEquals(1L, repeated.counters.distinctNewDepthTimestamps)
        assertEquals(1L, repeated.counters.reprojections); assertSame(first.lastNewDataStatistics, repeated.lastNewDataStatistics)
    }

    @Test fun changedTimestampIsNewAndIncrementsDistinctCount() {
        val tracker = tracker(); tracker.observe(ctx(), FakeSource(depth(50), confidence(51)))
        val state = tracker.observe(ctx(frame = 200), FakeSource(depth(60), confidence(61)))
        assertEquals(DepthDataKind.NEW, state.currentObservation?.dataKind); assertEquals(2L, state.counters.distinctNewDepthTimestamps)
    }

    @Test fun rateWindowIsBoundedAndRepeatedTimestampsDoNotEnterIt() {
        val tracker = RawDepthDiagnosticTracker(config(), 4, 10_000_000_000L)
        repeat(10) { i ->
            val t = (i + 1) * 100_000_000L
            tracker.observe(ctx(frame = t), FakeSource(depth(t), confidence(t)))
            tracker.observe(ctx(frame = t + 1), FakeSource(depth(t), confidence(t)))
        }
        val state = tracker.observe(ctx(), FakeSource(depth(1_100_000_000L), confidence(1_100_000_000L)))
        assertTrue(tracker.rateWindowSizeForTest() <= 4); assertEquals(11L, state.counters.distinctNewDepthTimestamps)
        assertEquals(10L, state.counters.reprojections); assertTrue(state.observedNewDepthRateHz > 0.0)
    }

    @Test fun trackingLossAndPauseClearCurrentDepthStatistics() {
        val tracker = tracker(); assertNotNull(tracker.observe(ctx(), FakeSource(depth(), confidence())).currentObservation)
        val lost = tracker.observe(ctx(false), FakeSource(depth(), confidence()))
        assertEquals(DepthAcquisitionStatus.NOT_TRACKING, lost.acquisitionStatus); assertNull(lost.currentObservation); assertNull(lost.lastNewDataStatistics)
        val paused = tracker.clearCurrent(DepthAcquisitionStatus.CONFIGURED_WAITING_FOR_DATA)
        assertNull(paused.currentObservation); assertNull(paused.lastNewDataStatistics)
    }

    private fun tracker() = RawDepthDiagnosticTracker(config())
    private fun config(raw: Boolean = true, auto: Boolean = true): DepthConfigurationDiagnostic {
        val mode = selectDiagnosticDepthMode(raw, auto)
        return DepthConfigurationDiagnostic(raw, auto, mode, mode,
            if (mode == DiagnosticDepthMode.NONE) DepthConfigurationStatus.UNSUPPORTED else DepthConfigurationStatus.CONFIGURED)
    }
    private fun ctx(tracking: Boolean = true, frame: Long = 100) = DepthFrameContext(
        frame, tracking, DiagnosticCameraIntrinsics(400.0,401.0,320.0,240.0,640,480),
        DiagnosticCameraIntrinsics(800.0,802.0,640.0,360.0,1280,720), 1, 1080, 2340)
    private fun depth(t: Long = 10) = image(2,2,t,HardwareBuffer.D_16, byteArrayOf(1,0,2,0,3,0,4,0),4,2)
    private fun confidence(t: Long = 11) = image(2,2,t,ImageFormat.Y8, byteArrayOf(1,127,128.toByte(),255.toByte()),2,1)
    private fun image(w:Int,h:Int,t:Long,f:Int,b:ByteArray,r:Int,p:Int) = FakeImage(w,h,t,f,b,r,p)
    private fun stats(d: DiagnosticDepthImage, c: DiagnosticDepthImage) =
        requireNotNull(tracker().observe(ctx(), FakeSource(d,c)).lastNewDataStatistics)

    private class FakeSource(private val d: DiagnosticDepthImage, private val c: DiagnosticDepthImage) : RawDepthFrameSource {
        var depthCalls=0; var confidenceCalls=0
        override fun acquireRawDepth16Bits() = d.also { depthCalls++ }
        override fun acquireRawDepthConfidence() = c.also { confidenceCalls++ }
    }
    private class ThrowingSource(private val reason: DepthFailureReason) : RawDepthFrameSource {
        override fun acquireRawDepth16Bits(): DiagnosticDepthImage = throw DepthAcquisitionException(reason)
        override fun acquireRawDepthConfidence(): DiagnosticDepthImage = error("unused")
    }
    private class FakeImage(
        override val width:Int, override val height:Int, override val timestampNanos:Long, override val format:Int,
        bytes:ByteArray, rowStride:Int, pixelStride:Int,
    ) : DiagnosticDepthImage {
        private val p = FakePlane(bytes,rowStride,pixelStride); var closeCalls=0
        override fun plane(): DiagnosticDepthPlane = p
        override fun close() { closeCalls++ }
    }
    private class FakePlane(private val b:ByteArray, override val rowStride:Int, override val pixelStride:Int) : DiagnosticDepthPlane {
        override val byteCount get()=b.size
        override fun unsignedByteAt(offset:Int)=b[offset].toInt() and 0xff
    }
}
