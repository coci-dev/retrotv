package com.joel.retrotv.input

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Player(val slot: Int, val name: String)

/** Puesto asignado a una conexión. [gen] distingue reconexiones que reutilizan el mismo slot. */
data class Seat(val slot: Int, val gen: Int)

/**
 * Punto central de input de los celulares.
 *
 * El servidor escribe aquí el estado completo de botones de cada jugador; el hub calcula
 * qué botones se presionaron/soltaron y se lo entrega a la pantalla activa ([Sink]) en el
 * hilo principal.
 */
object InputHub {

    interface Sink {
        /** [pressed]/[released] son bitmasks de [Pad]. [port] va de 0 a 3. */
        fun onButtons(port: Int, pressed: Int, released: Int)

        fun onMenu(port: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val masks = IntArray(Pad.MAX_PLAYERS)
    private val names = arrayOfNulls<String>(Pad.MAX_PLAYERS)
    private val ids = arrayOfNulls<String>(Pad.MAX_PLAYERS)
    private val gens = IntArray(Pad.MAX_PLAYERS)

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private var sink: Sink? = null

    fun setSink(newSink: Sink) {
        main.post { swapSink(newSink) }
    }

    /**
     * Quita [owner] solo si sigue siendo el sink activo. Al abrir otra pantalla, su onStart
     * corre antes que el onStop de la anterior, así que no se puede limpiar a ciegas.
     */
    fun clearSink(owner: Sink) {
        main.post { if (sink === owner) swapSink(null) }
    }

    private fun swapSink(newSink: Sink?) {
        // La pantalla saliente suelta todo lo presionado; la nueva parte de cero y recibe
        // los botones aún presionados con el siguiente keep-alive del celular.
        val held = synchronized(masks) { masks.copyOf().also { masks.fill(0) } }
        sink?.let { old -> held.forEachIndexed { port, m -> if (m != 0) old.onButtons(port, 0, m) } }
        sink = newSink
    }

    /**
     * Asigna un puesto: el mismo que tenía [id] si se está reconectando, o el libre más bajo.
     * Devuelve null si está lleno.
     */
    fun join(name: String, id: String): Seat? {
        val seat = synchronized(masks) {
            val slot = ids.indexOfFirst { id.isNotEmpty() && it == id }.takeIf { it >= 0 }
                ?: names.indexOfFirst { it == null }.takeIf { it >= 0 }
                ?: return null
            names[slot] = name
            ids[slot] = id
            gens[slot]++
            Seat(slot, gens[slot])
        }
        publishPlayers()
        return seat
    }

    fun leave(seat: Seat) {
        // Si otra conexión ya tomó este puesto (reconexión), no lo liberamos.
        if (synchronized(masks) { gens[seat.slot] != seat.gen }) return
        setMask(seat.slot, 0)
        synchronized(masks) {
            names[seat.slot] = null
            ids[seat.slot] = null
        }
        publishPlayers()
    }

    fun setMask(slot: Int, mask: Int) {
        val old = synchronized(masks) {
            val old = masks[slot]
            if (old == mask) return
            masks[slot] = mask
            old
        }
        val pressed = mask and old.inv()
        val released = old and mask.inv()
        main.post { sink?.onButtons(slot, pressed, released) }
    }

    fun menu(slot: Int) {
        main.post { sink?.onMenu(slot) }
    }

    private fun publishPlayers() {
        _players.value = synchronized(masks) {
            names.mapIndexedNotNull { i, n -> n?.let { Player(i, it) } }
        }
    }
}
