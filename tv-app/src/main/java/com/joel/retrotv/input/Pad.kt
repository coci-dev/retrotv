package com.joel.retrotv.input

/** Bits del bitmask de botones. Debe coincidir con docs/protocol.md. */
object Pad {
    const val UP = 1 shl 0
    const val DOWN = 1 shl 1
    const val LEFT = 1 shl 2
    const val RIGHT = 1 shl 3
    const val A = 1 shl 4
    const val B = 1 shl 5
    const val X = 1 shl 6
    const val Y = 1 shl 7
    const val L = 1 shl 8
    const val R = 1 shl 9
    const val START = 1 shl 10
    const val SELECT = 1 shl 11

    // Solo controles físicos (el control del celular no los tiene).
    const val L2 = 1 shl 12
    const val R2 = 1 shl 13

    val ALL = intArrayOf(UP, DOWN, LEFT, RIGHT, A, B, X, Y, L, R, START, SELECT, L2, R2)
    const val DIRECTIONS = UP or DOWN or LEFT or RIGHT

    const val MAX_PLAYERS = 4

    /** Itera los bits activos de [mask]. */
    inline fun forEachBit(mask: Int, action: (Int) -> Unit) {
        for (bit in ALL) if (mask and bit != 0) action(bit)
    }
}
