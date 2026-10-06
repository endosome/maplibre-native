package org.maplibre.android.maps

import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Map clicks with and without double-tap zoom, through the real gesture detectors: without double-tap zoom a click
 * comes as the finger lifts, and two quick taps are two clicks.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MapGestureDetectorTapTest {
    private lateinit var detector: MapGestureDetector
    private lateinit var uiSettings: UiSettings
    private lateinit var transform: Transform
    private val clicks = mutableListOf<LatLng>()

    @Before
    fun setUp() {
        uiSettings = Mockito.mock(UiSettings::class.java)
        Mockito.`when`(uiSettings.isZoomGesturesEnabled).thenReturn(true)
        Mockito.`when`(uiSettings.isQuickZoomGesturesEnabled).thenReturn(true)
        Mockito.`when`(uiSettings.isScrollGesturesEnabled).thenReturn(true)
        transform = Mockito.mock(Transform::class.java)
        val annotations = Mockito.mock(AnnotationManager::class.java)
        Mockito.`when`(annotations.onTap(any())).thenReturn(false)
        val projection = Mockito.mock(Projection::class.java)
        Mockito.`when`(projection.fromScreenLocation(any())).thenAnswer {
            val point = it.getArgument<PointF>(0)
            LatLng(point.y.toDouble(), point.x.toDouble())
        }
        detector = MapGestureDetector(
            RuntimeEnvironment.getApplication(),
            transform,
            projection,
            uiSettings,
            annotations,
            Mockito.mock(CameraChangeDispatcher::class.java)
        )
        detector.addOnMapClickListener { point ->
            clicks.add(point)
            true
        }
    }

    private fun doubleTapZoom(on: Boolean) {
        Mockito.`when`(uiSettings.isDoubleTapGesturesEnabled).thenReturn(on)
    }

    /** A tap at (x, y): down, then up [holdMs] later. */
    private fun tap(x: Float, y: Float, holdMs: Long = 50) {
        val down = SystemClock.uptimeMillis()
        detector.onTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        idle(holdMs)
        detector.onTouchEvent(MotionEvent.obtain(down, down + holdMs, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    @Test
    fun withDoubleTapZoomAClickWaitsForTheDoubleTapTimeout() {
        doubleTapZoom(true)
        tap(10f, 20f)
        assertEquals(0, clicks.size)
        idle(doubleTapTimeout)
        assertEquals(listOf(LatLng(20.0, 10.0)), clicks)
    }

    @Test
    fun withDoubleTapZoomTwoQuickTapsZoomAndClickNothing() {
        doubleTapZoom(true)
        tap(10f, 20f)
        idle(100)
        tap(12f, 21f)
        idle(doubleTapTimeout * 2)
        assertEquals(0, clicks.size)
        // the zoom animation starts from the current zoom
        Mockito.verify(transform, Mockito.atLeastOnce()).rawZoom
    }

    @Test
    fun withoutDoubleTapZoomAClickComesAsTheFingerLifts() {
        doubleTapZoom(false)
        tap(10f, 20f)
        assertEquals(listOf(LatLng(20.0, 10.0)), clicks)
        // and only once, when the double-tap timeout passes
        idle(doubleTapTimeout * 2)
        assertEquals(1, clicks.size)
    }

    @Test
    fun withoutDoubleTapZoomTwoQuickTapsAreTwoClicks() {
        doubleTapZoom(false)
        tap(10f, 20f)
        idle(100)
        tap(12f, 21f)
        idle(doubleTapTimeout * 2)
        assertEquals(listOf(LatLng(20.0, 10.0), LatLng(21.0, 12.0)), clicks)
    }

    @Test
    fun withoutDoubleTapZoomThreeQuickTapsAreThreeClicks() {
        doubleTapZoom(false)
        tap(10f, 20f)
        idle(100)
        tap(12f, 21f)
        idle(100)
        tap(14f, 22f)
        idle(doubleTapTimeout * 2)
        assertEquals(3, clicks.size)
    }

    @Test
    fun withoutDoubleTapZoomATapHeldPastTheDoubleTapTimeoutIsOneClick() {
        doubleTapZoom(false)
        tap(10f, 20f, holdMs = doubleTapTimeout + 50)
        idle(doubleTapTimeout * 2)
        assertEquals(1, clicks.size)
    }
}
