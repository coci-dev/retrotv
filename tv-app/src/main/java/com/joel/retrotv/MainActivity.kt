package com.joel.retrotv

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.joel.retrotv.game.GameActivity
import com.joel.retrotv.input.InputHub
import com.joel.retrotv.input.Pad
import com.joel.retrotv.library.Game
import com.joel.retrotv.library.usedCount
import com.joel.retrotv.ui.LibraryScreen
import com.joel.retrotv.ui.RetroColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var games by mutableStateOf<List<Game>?>(null)
    private var toast by mutableStateOf<String?>(null)
    private var optionsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RetroTheme {
                val serverFailed by app.server.failed.collectAsState()
                LibraryScreen(
                    games = games,
                    covers = app.covers,
                    serverFailed = serverFailed,
                    toast = toast,
                    onPlay = { GameActivity.start(this, it) },
                    onDelete = ::deleteGame,
                    savesOf = { app.library.slots(it).usedCount() },
                    onOptionsOpen = { optionsOpen = it },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Re-escanea al volver a la pantalla y cada vez que se sube un juego.
                app.library.version.collect {
                    games = withContext(Dispatchers.IO) { app.library.scan() }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.library.imports.collect { game -> showToast("¡NUEVO JUEGO! ${game.title.uppercase()}") }
            }
        }
    }

    private fun deleteGame(game: Game) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { app.library.delete(game) }
            showToast(if (ok) "JUEGO BORRADO" else "NO SE PUDO BORRAR")
        }
    }

    private fun showToast(text: String) {
        toast = text
        lifecycleScope.launch {
            delay(3_000)
            if (toast == text) toast = null
        }
    }

    override fun onStart() {
        super.onStart()
        InputHub.setSink(menuNavigation)
    }

    // Los controles físicos entran por InputHub, como los celulares.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        app.gamepads.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        app.gamepads.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    private fun superDispatchKey(event: KeyEvent) = super.dispatchKeyEvent(event)

    override fun onStop() {
        InputHub.clearSink(menuNavigation)
        super.onStop()
    }

    /** Con el celular también se navega la biblioteca: se traduce a teclas del control remoto. */
    private val menuNavigation = object : InputHub.Sink {
        override fun onButtons(port: Int, pressed: Int, released: Int) {
            Pad.forEachBit(pressed) { bit -> uiKey(bit)?.let { send(KeyEvent.ACTION_DOWN, it) } }
            Pad.forEachBit(released) { bit -> uiKey(bit)?.let { send(KeyEvent.ACTION_UP, it) } }
        }

        override fun onMenu(port: Int) = Unit

        private fun send(action: Int, keyCode: Int) {
            // super: estas teclas sintéticas no deben volver a pasar por Gamepads.
            superDispatchKey(KeyEvent(action, keyCode))
        }

        private fun uiKey(bit: Int): Int? = when (bit) {
            Pad.UP -> KeyEvent.KEYCODE_DPAD_UP
            Pad.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            Pad.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            Pad.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            Pad.A, Pad.START -> KeyEvent.KEYCODE_DPAD_CENTER
            // SELECT abre la ficha del juego (equivale a ☰ del control remoto).
            Pad.SELECT -> KeyEvent.KEYCODE_MENU
            // B solo hace "atrás" dentro de la ficha; en la biblioteca cerraría la app.
            Pad.B -> if (optionsOpen) KeyEvent.KEYCODE_BACK else null
            else -> null
        }
    }
}

@androidx.compose.runtime.Composable
fun RetroTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RetroColors.Yellow,
            background = RetroColors.Night,
            surface = RetroColors.Panel,
        ),
        content = content,
    )
}
