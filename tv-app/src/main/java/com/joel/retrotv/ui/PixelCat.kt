package com.joel.retrotv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Sonny: gatito atigrado gris-café con pecho y patas blancas, franja blanca en la cara y
 * collar verde. Cada carácter es un píxel:
 * K contorno · T atigrado · D raya oscura · W blanco · P nariz · E ojo · G collar · . nada
 */
private val HEAD = listOf(
    ".K..........K.",
    "KTK........KTK",
    "KTTKKKKKKKKTTK",
    "KTDTDTTTTDTDTK",
    "KDTDTDWWDTDTDK",
    "KTTTTTWWTTTTTK",
    "KTEEKTWWTKEETK",
    "KDTTTWWWWTTTDK",
    "KTDTWWPPWWTDTK",
    ".KTWWWWWWWWTK.",
    "..KKWWWWWWKK..",
    "..KGGGGGGGGK..",
)

private val BODY = listOf(
    ".KTDWWWWWWDTK.",
    ".KDTWWWWWWTDK.",
    ".KTDTWWWWTDTK.",
    ".KDTWWKKWWTDK.",
    ".KTDWWKKWWDTK.",
    ".KDTWWKKWDWTK.",
    ".KTDWWKKWWDTK.",
    ".KKKKKKKKKKKK.",
)

/** Cola rayada abajo (se agrega a la derecha de cada fila del cuerpo). */
private val TAIL_DOWN = listOf("....", "....", "....", "..K.", ".KDK", ".KTK", "KDK.", "KK..")

/** Cola arriba (meneo). */
private val TAIL_UP = listOf("..K.", ".KDK", ".KTK", ".KDK", "KTK.", "KDK.", "KK..", "....")

private val PALETTE = mapOf(
    'K' to Color(0xFF1A1424),
    'T' to Color(0xFF8C7A66),
    'D' to Color(0xFF3E342C),
    'W' to Color(0xFFF2EEE6),
    'P' to Color(0xFFA8665C),
    'E' to Color(0xFFB9B56E),
    'G' to Color(0xFF2FA552),
)

/** Gatito que mueve la cola y parpadea de vez en cuando. [pixel] es el tamaño de cada píxel. */
@Composable
fun PixelCat(modifier: Modifier = Modifier, pixel: Dp = 3.dp) {
    var tailUp by remember { mutableStateOf(false) }
    var blinking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            tailUp = !tailUp
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_200)
            blinking = true
            delay(160)
            blinking = false
        }
    }

    val tail = if (tailUp) TAIL_UP else TAIL_DOWN
    val rows = HEAD.map { "$it...." } + BODY.mapIndexed { i, row -> row + tail[i] }
    Canvas(modifier.size(pixel * rows[0].length, pixel * rows.size)) {
        val p = pixel.toPx()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                // Al parpadear, los ojos se vuelven una línea oscura.
                val ch = if (blinking && c == 'E') 'K' else c
                val color = PALETTE[ch] ?: return@forEachIndexed
                drawRect(color, Offset(x * p, y * p), Size(p + 0.5f, p + 0.5f))
            }
        }
    }
}
