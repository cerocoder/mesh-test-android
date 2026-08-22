package com.cerocoder.meshtest

import android.app.Application

class MeshTestApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(isDebugBuild = BuildConfig.DEBUG)
    }
}
