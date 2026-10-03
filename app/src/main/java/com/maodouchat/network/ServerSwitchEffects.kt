package com.maodouchat.network

import com.maodouchat.MaodouchatApp

object ServerSwitchEffects {

    fun applyAfterServerChanged() {
        MaodouchatApp.instance.rebuildImageLoader()
        MaodouchatApp.instance.disconnectRealtime()
    }
}
