package com.joel.retrotv

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.joel.retrotv.input.Gamepads
import com.joel.retrotv.library.Covers
import com.joel.retrotv.library.GameLibrary
import com.joel.retrotv.server.ControllerServer

class RetroTvApp : Application() {

    lateinit var library: GameLibrary
        private set
    lateinit var covers: Covers
        private set
    lateinit var server: ControllerServer
        private set
    lateinit var gamepads: Gamepads
        private set

    override fun onCreate() {
        super.onCreate()
        library = GameLibrary(this)
        covers = Covers(this)
        server = ControllerServer(this, library)
        gamepads = Gamepads(this).also { it.start() }

        // El servidor vive mientras la app está en primer plano (biblioteca o juego).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = server.start()
            override fun onStop(owner: LifecycleOwner) = server.stop()
        })
    }
}

val Context.app: RetroTvApp get() = applicationContext as RetroTvApp
