package com.maodouchat.call

import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.WebRTCManager
import io.mockk.mockk
import io.mockk.verify
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
}
