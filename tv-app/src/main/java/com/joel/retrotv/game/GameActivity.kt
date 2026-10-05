package com.joel.retrotv.game

import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.joel.retrotv.RetroTheme
import com.joel.retrotv.app
import com.joel.retrotv.input.InputHub
import com.joel.retrotv.input.Pad
import com.joel.retrotv.library.Game
import com.joel.retrotv.library.GameLibrary
import com.joel.retrotv.library.GameSystem
import com.joel.retrotv.library.SaveSlot
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pantalla de juego: GLRetroView (LibretroDroid) con un overlay de Compose para el menú de pausa.
 *
 * Input:
 * - Celulares → [InputHub] → sendKeyEvent en el puerto del jugador (0-3).
 * - Control remoto → puerto 0. D-pad = D-pad, OK = A, Menú/Play = START, Atrás = menú de pausa.
 * - Gamepads Bluetooth → puerto según controllerNumber.
 */
class GameActivity : ComponentActivity() {

    private lateinit var game: Game
    private lateinit var library: GameLibrary
    private lateinit var retroView: GLRetroView

    /** Página visible del menú de pausa, o null si se está jugando. */
    private var menuPage by mutableStateOf<MenuPage?>(null)
    private val menuOpen get() = menuPage != null
    private var toast by mutableStateOf<String?>(null)
    private var speed by mutableIntStateOf(1)
    private var slots by mutableStateOf<List<SaveSlot>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_ROM_PATH)?.let(::File)
        val system = file?.let(GameSystem::forFile)
        if (file == null || system == null || !file.exists()) {
            finish()
            return
        }
        game = Game(file, system)
        library = app.library
        library.markPlayed(game)
        slots = library.slots(game)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val data = GLRetroViewData(this).apply {
            coreFilePath = system.coreFileName
            gameFilePath = file.absolutePath
            systemDirectory = library.systemDir.absolutePath
            savesDirectory = library.savesDir.absolutePath
            saveRAMState = library.sramFile(game).takeIf { it.exists() }?.readBytes()
            // Píxeles nítidos al escalar a la resolución de la TV.
            shader = ShaderConfig.Sharp
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        val overlay = ComposeView(this).apply {
            setContent {
                RetroTheme {
                    GameOverlay(
                        title = game.title,
                        systemName = system.displayName,
                        page = menuPage,
                        slots = slots,
                        speed = speed,
                        toast = toast,
                        actions = MenuActions(
                            onResume = ::closeMenu,
                            openSave = { menuPage = MenuPage.SAVE },
                            openLoad = { menuPage = MenuPage.LOAD },
                            onSave = ::saveState,
                            onLoad = ::loadState,
                            onSpeed = ::cycleSpeed,
                            onReset = { retroView.reset(); closeMenu() },
                            onExit = ::finish,
                            onBack = ::menuBack,
                        ),
                    )
                }
            }
        }

        setContentView(FrameLayout(this).apply {
            addView(retroView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        })

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code ->
                Toast.makeText(this@GameActivity, "Error del emulador ($code)", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        lifecycleScope.launch {
            var previous = InputHub.players.value.map { it.slot }.toSet()
            InputHub.players.collect { players ->
                val now = players.map { it.slot }.toSet()
                (now - previous).forEach { showToast("${it + 1}P CONECTADO") }
                (previous - now).forEach { showToast("${it + 1}P DESCONECTADO") }
                previous = now
            }
        }
        showToast("ATRÁS: MENÚ · OK: A · ☰: START")
    }

    override fun onStart() {
        super.onStart()
        InputHub.setSink(phoneInput)
    }

    override fun onStop() {
        InputHub.clearSink(phoneInput)
        if (::retroView.isInitialized) releaseAllCoreInput()
        super.onStop()
    }

    override fun onPause() {
        if (::retroView.isInitialized) {
            // Partidas con batería (SRAM) del juego; vacío si el juego no tiene.
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) library.sramFile(game).writeBytes(sram)
        }
        super.onPause()
    }

    // ---- Celulares ----

    private val phoneInput = object : InputHub.Sink {
        override fun onButtons(port: Int, pressed: Int, released: Int) {
            // Las liberaciones siempre van al core para que ningún botón quede pegado.
            Pad.forEachBit(released) { retroView.sendKeyEvent(KeyEvent.ACTION_UP, retroKey(it), port) }
            if (menuOpen) {
                Pad.forEachBit(pressed) { menuKey(it) }
            } else {
                Pad.forEachBit(pressed) { retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, retroKey(it), port) }
            }
        }

        override fun onMenu(port: Int) {
            if (menuOpen) closeMenu() else openMenu()
        }

        /** En el menú, el celular navega como si fuera el control remoto. */
        private fun menuKey(bit: Int) {
            val key = when (bit) {
                Pad.UP -> KeyEvent.KEYCODE_DPAD_UP
                Pad.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                Pad.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                Pad.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                Pad.A, Pad.START -> KeyEvent.KEYCODE_DPAD_CENTER
                Pad.B -> return menuBack()
                else -> return
            }
            superDispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, key))
            superDispatchKey(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    // ---- Control remoto y gamepads ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Los controles físicos entran por InputHub (puestos compartidos con los celulares).
        if (app.gamepads.handleKey(event)) return true
        val isMenuKey = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        if (isMenuKey) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (menuOpen) menuBack() else openMenu()
            }
            return true
        }
        if (menuOpen) return super.dispatchKeyEvent(event)

        // Control remoto de la TV: siempre es el puerto 0.
        val key = remoteKey(event) ?: return super.dispatchKeyEvent(event)
        if (event.repeatCount == 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        ) {
            retroView.sendKeyEvent(event.action, key, 0)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        app.gamepads.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    private fun superDispatchKey(event: KeyEvent) = super.dispatchKeyEvent(event)

    // ---- Menú de pausa ----

    private fun openMenu() {
        if (menuOpen) return
        menuPage = MenuPage.MAIN
        slots = library.slots(game)
        // Con el menú abierto, los "soltar" del control remoto/gamepad van a Compose y no al core.
        releaseAllCoreInput()
        // LibretroDroid no expone pause(): la emulación avanza en onDrawFrame, así que
        // dejar de dibujar la detiene.
        retroView.audioEnabled = false
        retroView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun releaseAllCoreInput() {
        for (port in 0 until Pad.MAX_PLAYERS) {
            for (bit in Pad.ALL) retroView.sendKeyEvent(KeyEvent.ACTION_UP, retroKey(bit), port)
        }
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuPage = null
        retroView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        retroView.audioEnabled = true
    }

    /** Atrás / B: de una sub-página vuelve al menú principal; del principal, al juego. */
    private fun menuBack() {
        if (menuPage == MenuPage.MAIN) closeMenu() else menuPage = MenuPage.MAIN
    }

    private fun cycleSpeed() {
        speed = if (speed >= MAX_SPEED) 1 else speed + 1
        retroView.frameSpeed = speed
    }

    private fun slotFile(index: Int) = library.slots(game).first { it.index == index }.file

    private fun saveState(index: Int) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { slotFile(index).writeBytes(retroView.serializeState()) }.isSuccess
            }
            slots = library.slots(game)
            showToast(if (ok) "GUARDADO EN RANURA $index" else "NO SE PUDO GUARDAR")
            closeMenu()
        }
    }

    private fun loadState(index: Int) {
        lifecycleScope.launch {
            val file = slotFile(index)
            if (!file.exists()) {
                showToast("RANURA $index VACÍA")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching { retroView.unserializeState(file.readBytes()) }.getOrDefault(false)
            }
            showToast(if (ok) "RANURA $index CARGADA" else "NO SE PUDO CARGAR")
            closeMenu()
        }
    }

    private fun showToast(text: String) {
        toast = text
        lifecycleScope.launch {
            delay(3_000)
            if (toast == text) toast = null
        }
    }

    companion object {
        private const val EXTRA_ROM_PATH = "rom_path"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val MAX_SPEED = 3

        fun start(context: Context, game: Game) {
            context.startActivity(
                Intent(context, GameActivity::class.java).putExtra(EXTRA_ROM_PATH, game.file.absolutePath),
            )
        }

        /** Botón del celular → keycode que LibretroDroid traduce al RetroPad. */
        private fun retroKey(bit: Int): Int = when (bit) {
            Pad.UP -> KeyEvent.KEYCODE_DPAD_UP
            Pad.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            Pad.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            Pad.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            Pad.A -> KeyEvent.KEYCODE_BUTTON_A
            Pad.B -> KeyEvent.KEYCODE_BUTTON_B
            Pad.X -> KeyEvent.KEYCODE_BUTTON_X
            Pad.Y -> KeyEvent.KEYCODE_BUTTON_Y
            Pad.L -> KeyEvent.KEYCODE_BUTTON_L1
            Pad.R -> KeyEvent.KEYCODE_BUTTON_R1
            Pad.START -> KeyEvent.KEYCODE_BUTTON_START
            Pad.L2 -> KeyEvent.KEYCODE_BUTTON_L2
            Pad.R2 -> KeyEvent.KEYCODE_BUTTON_R2
            else -> KeyEvent.KEYCODE_BUTTON_SELECT
        }

        /** Teclas del control remoto → keycode de LibretroDroid, o null si no es de juego (volumen, etc.). */
        private fun remoteKey(event: KeyEvent): Int? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> event.keyCode
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> KeyEvent.KEYCODE_BUTTON_START
            else -> null
        }
    }
}
