package com.joel.retrotv.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Controles físicos (USB o Bluetooth) conectados a la TV.
 *
 * Cada control toma un puesto de jugador en [InputHub], igual que un celular, y su estado se
 * entrega como el mismo bitmask de [Pad]. Así biblioteca, juego y menús los tratan igual.
 * El control remoto de la TV no cuenta: no tiene botones de gamepad.
 *
 * Todo corre en el hilo principal (eventos de input y callbacks de InputManager).
 */
class Gamepads(context: Context) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(InputManager::class.java)

    private val seats = mutableMapOf<Int, Seat>()
    private val keyMasks = mutableMapOf<Int, Int>()
    private val axisMasks = mutableMapOf<Int, Int>()

    /** Controles con SELECT+START presionados: esos dos botones se ocultan hasta soltarlos. */
    private val comboHeld = mutableSetOf<Int>()

    fun start() {
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        inputManager.inputDeviceIds.forEach(::onInputDeviceAdded)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isGamepad(device) || deviceId in seats) return
        seatFor(device)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val seat = seats.remove(deviceId) ?: return
        keyMasks.remove(deviceId)
        axisMasks.remove(deviceId)
        comboHeld.remove(deviceId)
        Log.i(TAG, "Control desconectado del puesto ${seat.slot + 1}P")
        InputHub.leave(seat)
    }

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    /** Devuelve true si el evento era de un control y ya se procesó. */
    fun handleKey(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!isGamepad(device)) return false
        val bit = keyBit(event.keyCode) ?: return false
        if (event.repeatCount > 0) return true

        val old = keyMasks[device.id] ?: 0
        keyMasks[device.id] = when (event.action) {
            KeyEvent.ACTION_DOWN -> old or bit
            KeyEvent.ACTION_UP -> old and bit.inv()
            else -> return true
        }
        publish(device)
        return true
    }

    /** Cruceta como ejes (HAT) y stick izquierdo → direcciones. */
    fun handleMotion(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!isGamepad(device) || event.action != MotionEvent.ACTION_MOVE) return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false

        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { it != 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_X).takeIf { abs(it) > STICK_THRESHOLD } ?: 0f
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { it != 0f }
            ?: event.getAxisValue(MotionEvent.AXIS_Y).takeIf { abs(it) > STICK_THRESHOLD } ?: 0f
        var mask = 0
        if (x < 0) mask = mask or Pad.LEFT
        if (x > 0) mask = mask or Pad.RIGHT
        if (y < 0) mask = mask or Pad.UP
        if (y > 0) mask = mask or Pad.DOWN
        // Gatillos analógicos (algunos controles no mandan BUTTON_L2/R2).
        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > TRIGGER_THRESHOLD ||
            event.getAxisValue(MotionEvent.AXIS_BRAKE) > TRIGGER_THRESHOLD
        ) mask = mask or Pad.L2
        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > TRIGGER_THRESHOLD ||
            event.getAxisValue(MotionEvent.AXIS_GAS) > TRIGGER_THRESHOLD
        ) mask = mask or Pad.R2

        if (axisMasks[device.id] != mask) {
            axisMasks[device.id] = mask
            publish(device)
        }
        return true
    }

    private fun publish(device: InputDevice) {
        val seat = seatFor(device) ?: return
        var mask = (keyMasks[device.id] ?: 0) or (axisMasks[device.id] ?: 0)

        // SELECT + START = menú de pausa. Esos botones no llegan al juego hasta soltarlos.
        val combo = Pad.SELECT or Pad.START
        if (mask and combo == combo && device.id !in comboHeld) {
            comboHeld += device.id
            InputHub.menu(seat.slot)
        }
        if (device.id in comboHeld) {
            if (mask and combo == 0) comboHeld -= device.id
            mask = mask and combo.inv()
        }
        InputHub.setMask(seat.slot, mask)
    }

    private fun seatFor(device: InputDevice): Seat? {
        seats[device.id]?.let { return it }
        // El descriptor es estable entre reconexiones: el control recupera su puesto.
        val seat = InputHub.join(device.name.take(32), "pad:${device.descriptor}") ?: run {
            Log.w(TAG, "Sin puestos libres para ${device.name}")
            return null
        }
        Log.i(TAG, "Control \"${device.name}\" → ${seat.slot + 1}P")
        seats[device.id] = seat
        return seat
    }

    companion object {
        private const val TAG = "Gamepads"
        private const val STICK_THRESHOLD = 0.5f
        private const val TRIGGER_THRESHOLD = 0.5f

        fun isGamepad(device: InputDevice): Boolean {
            if (device.isVirtual) return false
            val sources = device.sources
            val gamepadSource = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            // Exigir el botón A deja fuera controles remotos que se anuncian como gamepad.
            if (!gamepadSource || !device.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0]) return false
            // Algunas TVs traen dispositivos internos que dicen ser gamepad (en la Xiaomi:
            // "virtual-search"). Un control real tiene ejes de joystick o está conectado por
            // USB/Bluetooth.
            val hasJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            val external = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && device.isExternal
            return hasJoystick || external
        }

        /**
         * Botón del control → bit de [Pad]. Android llama A al botón de abajo (estilo Xbox);
         * en Nintendo ese es B, así que se cruzan A↔B y X↔Y.
         */
        private fun keyBit(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> Pad.B
            KeyEvent.KEYCODE_BUTTON_B -> Pad.A
            KeyEvent.KEYCODE_BUTTON_X -> Pad.Y
            KeyEvent.KEYCODE_BUTTON_Y -> Pad.X
            KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L
            KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R
            KeyEvent.KEYCODE_BUTTON_L2 -> Pad.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> Pad.R2
            KeyEvent.KEYCODE_BUTTON_START -> Pad.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.SELECT
            else -> null
        }
    }
}
