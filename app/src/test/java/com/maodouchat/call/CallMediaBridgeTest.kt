package com.maodouchat.call

import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.WebRTCManager
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.webrtc.SurfaceViewRenderer

class CallMediaBridgeTest {

    @Test
    fun `bind and release manage manager lifecycle`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(manager)
        assertSame(manager, bridge.manager)
        bridge.release()
        verify(exactly = 1) { manager.release() }
        assertNull(bridge.manager)
    }

    @Test
    fun `media intents delegate to bound manager`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        val renderer = mockk<SurfaceViewRenderer>(relaxed = true)
        bridge.bind(manager)

        bridge.toggleMute(true)
        bridge.toggleVideo(false)
        bridge.switchCamera()
        bridge.selectAudioRoute(CallAudioRoute.SPEAKER)
        bridge.attachLocalRenderer(renderer)
        bridge.attachRemoteRenderer(renderer)
        bridge.attachGroupRemoteRenderer("u1", renderer)
        bridge.detachGroupRemoteRenderer("u1", renderer)
        bridge.detachLocalRenderer(renderer)
        bridge.detachRemoteRenderer(renderer)

        verify(exactly = 1) { manager.toggleMute(true) }
        verify(exactly = 1) { manager.toggleVideo(false) }
        verify(exactly = 1) { manager.switchCamera() }
        verify(exactly = 1) { manager.selectAudioRoute(CallAudioRoute.SPEAKER) }
        verify(exactly = 1) { manager.attachLocalRenderer(renderer) }
        verify(exactly = 1) { manager.attachRemoteRenderer(renderer) }
        verify(exactly = 1) { manager.attachGroupRemoteRenderer("u1", renderer) }
        verify(exactly = 1) { manager.detachGroupRemoteRenderer("u1", renderer) }
        verify(exactly = 1) { manager.detachLocalRenderer(renderer) }
        verify(exactly = 1) { manager.detachRemoteRenderer(renderer) }
    }

    @Test
    fun `intents are no-ops without a bound manager`() {
        val bridge = CallMediaBridge()
        bridge.toggleMute(true)
        bridge.switchCamera()
        assertNull(bridge.manager)
    }

    @Test
    fun `rebind swaps the manager and release only touches the latest`() {
        val bridge = CallMediaBridge()
        val first = mockk<WebRTCManager>(relaxed = true)
        val second = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(first)
        bridge.bind(second)
        assertSame(second, bridge.manager)

        bridge.release()

        verify(exactly = 0) { first.release() }
        verify(exactly = 1) { second.release() }
        assertNull(bridge.manager)
    }

    @Test
    fun `release without a bound manager is a silent no-op`() {
        val bridge = CallMediaBridge()
        bridge.release()
        assertNull(bridge.manager)
    }

    @Test
    fun `double release calls manager release exactly once`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(manager)

        bridge.release()
        bridge.release()

        verify(exactly = 1) { manager.release() }
    }

    @Test
    fun `intents after release never reach the manager`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        val renderer = mockk<SurfaceViewRenderer>(relaxed = true)
        bridge.bind(manager)
        bridge.release()

        bridge.toggleMute(true)
        bridge.toggleVideo(true)
        bridge.switchCamera()
        bridge.selectAudioRoute(CallAudioRoute.SPEAKER)
        bridge.attachLocalRenderer(renderer)

        verify(exactly = 0) { manager.toggleMute(any()) }
        verify(exactly = 0) { manager.toggleVideo(any()) }
        verify(exactly = 0) { manager.switchCamera() }
        verify(exactly = 0) { manager.selectAudioRoute(any()) }
        verify(exactly = 0) { manager.attachLocalRenderer(any()) }
    }

    @Test
    fun `mute and video intents carry exact boolean values`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(manager)

        bridge.toggleMute(true)
        bridge.toggleMute(false)
        bridge.toggleVideo(true)

        verify(exactly = 1) { manager.toggleMute(true) }
        verify(exactly = 1) { manager.toggleMute(false) }
        verify(exactly = 1) { manager.toggleVideo(true) }
    }

    @Test
    fun `rebind does not release the old manager`() {
        val bridge = CallMediaBridge()
        val first = mockk<WebRTCManager>(relaxed = true)
        val second = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(first)
        bridge.bind(second)

        verify(exactly = 0) { first.release() }
        assertSame(second, bridge.manager)
    }

    @Test
    fun `intents after rebind reach only the latest manager`() {
        val bridge = CallMediaBridge()
        val first = mockk<WebRTCManager>(relaxed = true)
        val second = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(first)
        bridge.bind(second)

        bridge.toggleMute(true)

        verify(exactly = 0) { first.toggleMute(any()) }
        verify(exactly = 1) { second.toggleMute(true) }
    }

    @Test
    fun `media intents keep call order`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(manager)

        bridge.toggleMute(true)
        bridge.toggleMute(false)
        bridge.toggleVideo(true)

        verifyOrder {
            manager.toggleMute(true)
            manager.toggleMute(false)
            manager.toggleVideo(true)
        }
    }

    @Test
    fun `group renderer intents carry exact user id including blank`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        val renderer = mockk<SurfaceViewRenderer>(relaxed = true)
        bridge.bind(manager)

        bridge.attachGroupRemoteRenderer("u-1", renderer)
        bridge.detachGroupRemoteRenderer("", renderer)

        verify(exactly = 1) { manager.attachGroupRemoteRenderer("u-1", renderer) }
        verify(exactly = 1) { manager.detachGroupRemoteRenderer("", renderer) }
    }

    @Test
    fun `select audio route forwards every route value`() {
        val bridge = CallMediaBridge()
        val manager = mockk<WebRTCManager>(relaxed = true)
        bridge.bind(manager)

        CallAudioRoute.entries.forEach { bridge.selectAudioRoute(it) }

        CallAudioRoute.entries.forEach { route ->
            verify(exactly = 1) { manager.selectAudioRoute(route) }
        }
    }
}
