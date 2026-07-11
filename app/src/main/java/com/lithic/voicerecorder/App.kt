package com.lithic.voicerecorder

import android.app.Application
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set

        val context: Context get() = instance.applicationContext
    }
}
