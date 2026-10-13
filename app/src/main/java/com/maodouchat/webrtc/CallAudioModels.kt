package com.maodouchat.webrtc

enum class CallAudioRoute { BLUETOOTH, WIRED, EARPIECE, SPEAKER }

object CallAudioRoutePolicy {
    fun preferred(available: Set<CallAudioRoute>, speakerPreferred: Boolean): CallAudioRoute =
        candidates(available, speakerPreferred, manualRoute = null).first()

    fun selected(
        available: Set<CallAudioRoute>,
        speakerPreferred: Boolean,
        manualRoute: CallAudioRoute?
    ): CallAudioRoute = candidates(available, speakerPreferred, manualRoute).first()

    fun candidates(
        available: Set<CallAudioRoute>,
        speakerPreferred: Boolean,
        manualRoute: CallAudioRoute?
    ): List<CallAudioRoute> = buildList {
        manualRoute?.takeIf { it in available }?.let(::add)
        listOfNotNull(
            CallAudioRoute.BLUETOOTH.takeIf { it in available },
            CallAudioRoute.WIRED.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { speakerPreferred && it in available },
            CallAudioRoute.EARPIECE.takeIf { it in available },
            CallAudioRoute.SPEAKER.takeIf { it in available }
        ).forEach { route -> if (route !in this) add(route) }
        available.forEach { route -> if (route !in this) add(route) }
        if (isEmpty()) add(CallAudioRoute.SPEAKER)
    }
}
