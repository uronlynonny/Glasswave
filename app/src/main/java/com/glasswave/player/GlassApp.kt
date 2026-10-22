package com.glasswave.player

import android.app.Application

class GlassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread { YtRepo.init() }.start()
    }
}
