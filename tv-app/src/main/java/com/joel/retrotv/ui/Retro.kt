package com.joel.retrotv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.joel.retrotv.R

/** Paleta retro: fondo azul noche, texto crema y acentos tipo NES/SNES. */
object RetroColors {
    val Night = Color(0xFF0F0B1E)
    val NightLight = Color(0xFF1B1533)
    val Panel = Color(0xFF231B42)
    val PanelLight = Color(0xFF2F2557)
    val Border = Color(0xFF4A3D82)
    val Cream = Color(0xFFF4EBD0)
    val Muted = Color(0xFF9C91C4)
    val Yellow = Color(0xFFFFD23F)
    val Red = Color(0xFFE4404A)
    val Green = Color(0xFF4CC26B)
    val Blue = Color(0xFF3A8DF2)
    val Shadow = Color(0xFF07050F)

    val Players = listOf(Red, Blue, Green, Yellow)
}

/** Press Start 2P: títulos y etiquetas. */
val PixelFont = FontFamily(Font(R.font.press_start_2p))

/** VT323: texto más largo, estilo terminal. */
val TermFont = FontFamily(Font(R.font.vt323))

/** Texto pixelado con sombra sólida desplazada (sin difuminar), como en los 8 bits. */
@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    color: Color = RetroColors.Cream,
    shadow: Color? = RetroColors.Shadow,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        stripAccents(text),
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        style = TextStyle(
            fontFamily = PixelFont,
            fontSize = size,
            lineHeight = size * 1.5f,
            color = color,
            shadow = shadow?.let { Shadow(it, Offset(size.value / 4, size.value / 4), 0f) },
        ),
    )
}

/** Press Start 2P dibuja mal las vocales con tilde: se quitan (la Ñ se conserva). */
private fun stripAccents(text: String): String =
    java.text.Normalizer.normalize(text.replace('Ñ', '\u0001').replace('ñ', '\u0002'), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('\u0001', 'Ñ')
        .replace('\u0002', 'ñ')

@Composable
fun TermText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    color: Color = RetroColors.Muted,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        style = TextStyle(fontFamily = TermFont, fontSize = size, lineHeight = size * 1.1f, color = color),
    )
}

/**
 * Panel con esquinas "mordidas" (borde pixelado), relleno y sombra dura abajo a la derecha.
 */
fun Modifier.pixelPanel(
    fill: Color = RetroColors.Panel,
    border: Color = RetroColors.Border,
    px: Dp = 3.dp,
    shadow: Color? = RetroColors.Shadow,
): Modifier = drawBehind {
    val p = px.toPx()
    if (shadow != null) notchedRect(shadow, Offset(p * 1.5f, p * 1.5f), size, p)
    notchedRect(border, Offset.Zero, size, p)
    drawRect(fill, Offset(p, p), Size(size.width - 2 * p, size.height - 2 * p))
}

/** Rectángulo con las 4 esquinas recortadas un "píxel" de tamaño [p]. */
private fun DrawScope.notchedRect(color: Color, offset: Offset, size: Size, p: Float) {
    drawRect(color, offset + Offset(p, 0f), Size(size.width - 2 * p, size.height))
    drawRect(color, offset + Offset(0f, p), Size(size.width, size.height - 2 * p))
}

/** Líneas horizontales oscuras tipo monitor CRT sobre el contenido. */
fun Modifier.scanlines(alpha: Float = 0.16f, spacing: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val step = spacing.toPx()
    val line = (step / 2.5f).coerceAtLeast(1f)
    val color = Color.Black.copy(alpha = alpha)
    var y = 0f
    while (y < size.height) {
        drawRect(color, Offset(0f, y), Size(size.width, line))
        y += step
    }
}

/** 1f/0f alternando de golpe (sin fundido), para textos tipo "PRESS START". */
@Composable
fun rememberBlink(periodMs: Int = 1000): Boolean {
    val t by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "blink",
    )
    return t < 0.6f
}

/**
 * Elemento enfocable con el control remoto, sin ripple. [content] recibe si tiene el foco.
 */
@Composable
fun RetroFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (focused) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onFocus() }
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        content(focused)
    }
}
