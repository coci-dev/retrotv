package com.joel.retrotv.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joel.retrotv.library.SaveSlot
import com.joel.retrotv.ui.PixelText
import com.joel.retrotv.ui.RetroColors
import com.joel.retrotv.ui.RetroFocusable
import com.joel.retrotv.ui.TermText
import com.joel.retrotv.ui.pixelPanel
import com.joel.retrotv.ui.rememberBlink
import com.joel.retrotv.ui.scanlines
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MenuPage { MAIN, SAVE, LOAD }

@Composable
fun GameOverlay(
    title: String,
    systemName: String,
    page: MenuPage?,
    slots: List<SaveSlot>,
    speed: Int,
    toast: String?,
    actions: MenuActions,
) {
    Box(Modifier.fillMaxSize()) {
        if (speed > 1 && page == null) {
            PixelText(
                "▶▶ x$speed",
                size = 10.sp,
                color = RetroColors.Yellow,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }
        if (page != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xC80F0B1E)).scanlines(),
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    MenuPage.MAIN -> MainMenu(title, systemName, speed, actions)
                    MenuPage.SAVE -> SlotMenu("GUARDAR", slots, saving = true, onPick = actions.onSave, onBack = actions.onBack)
                    MenuPage.LOAD -> SlotMenu("CARGAR", slots, saving = false, onPick = actions.onLoad, onBack = actions.onBack)
                }
            }
        }
        if (toast != null) {
            PixelText(
                toast,
                size = 9.sp,
                color = RetroColors.Night,
                shadow = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .pixelPanel(fill = RetroColors.Yellow, border = RetroColors.Shadow)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

class MenuActions(
    val onResume: () -> Unit,
    val openSave: () -> Unit,
    val openLoad: () -> Unit,
    val onSave: (Int) -> Unit,
    val onLoad: (Int) -> Unit,
    val onSpeed: () -> Unit,
    val onReset: () -> Unit,
    val onExit: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
private fun MainMenu(title: String, systemName: String, speed: Int, actions: MenuActions) {
    MenuBox {
        PixelText("PAUSA", size = 18.sp, color = RetroColors.Yellow, shadow = RetroColors.Red)
        Spacer(Modifier.height(6.dp))
        TermText("$title · $systemName", size = 18.sp, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem("CONTINUAR", actions.onResume, first = true)
            MenuItem("GUARDAR PARTIDA", actions.openSave)
            MenuItem("CARGAR PARTIDA", actions.openLoad)
            MenuItem("VELOCIDAD", actions.onSpeed, value = "x$speed")
            MenuItem("REINICIAR", actions.onReset)
            MenuItem("SALIR", actions.onExit)
        }
    }
}

@Composable
private fun SlotMenu(
    title: String,
    slots: List<SaveSlot>,
    saving: Boolean,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val format = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    MenuBox {
        PixelText(title, size = 16.sp, color = RetroColors.Yellow, shadow = RetroColors.Red)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slots.forEachIndexed { i, slot ->
                val savedAt = slot.savedAt
                MenuItem(
                    "RANURA ${slot.index}",
                    onClick = { onPick(slot.index) },
                    value = savedAt?.let { format.format(Date(it)) } ?: "VACÍA",
                    enabled = saving || savedAt != null,
                    first = i == 0,
                )
            }
            Spacer(Modifier.height(6.dp))
            MenuItem("VOLVER", onBack)
        }
    }
}

@Composable
private fun MenuBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(380.dp)
            .pixelPanel(px = 4.dp)
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        content()
    }
}

@Composable
private fun MenuItem(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    enabled: Boolean = true,
    first: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val blink = rememberBlink(700)

    RetroFocusable(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .then(if (focused) Modifier.background(RetroColors.PanelLight) else Modifier)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(
                if (focused && blink) "▶" else " ",
                size = 10.sp,
                color = RetroColors.Yellow,
                shadow = null,
                modifier = Modifier.width(22.dp),
            )
            val color = when {
                !enabled -> RetroColors.Border
                focused -> RetroColors.Yellow
                else -> RetroColors.Cream
            }
            PixelText(label, size = 10.sp, color = color, modifier = Modifier.weight(1f))
            if (value != null) TermText(value, size = 18.sp, color = if (enabled) RetroColors.Muted else RetroColors.Border)
        }
    }
}
