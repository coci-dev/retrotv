package com.joel.retrotv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joel.retrotv.input.InputHub
import com.joel.retrotv.input.Pad
import com.joel.retrotv.library.Covers
import com.joel.retrotv.library.Game
import com.joel.retrotv.library.GameSystem
import com.joel.retrotv.server.Network
import kotlinx.coroutines.delay

/** Una pestaña de la biblioteca. */
private data class Shelf(val key: String, val label: String, val color: Color, val games: List<Game>)

private fun shelvesFor(games: List<Game>): List<Shelf> = buildList {
    val recent = games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(10)
    if (recent.isNotEmpty()) add(Shelf("recent", "RECIENTES", RetroColors.Yellow, recent))
    add(Shelf("all", "TODOS", RetroColors.Cream, games))
    for (system in GameSystem.entries) {
        val list = games.filter { it.system == system }
        if (list.isNotEmpty()) add(Shelf(system.name, system.shortName, Color(system.color), list))
    }
}

@Composable
fun LibraryScreen(
    games: List<Game>?,
    covers: Covers,
    serverFailed: Boolean,
    toast: String?,
    onPlay: (Game) -> Unit,
    onDelete: (Game) -> Unit,
    savesOf: (Game) -> Int,
    onOptionsOpen: (Boolean) -> Unit,
) {
    // Ficha abierta y el cartucho al que devolver el foco al cerrarla.
    var options by remember { mutableStateOf<Pair<Game, FocusRequester>?>(null) }
    LaunchedEffect(options != null) { onOptionsOpen(options != null) }

    Box(Modifier.fillMaxSize()) {
        // Scanlines solo en el fondo: el texto y el QR quedan nítidos.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(RetroColors.NightLight, RetroColors.Night)))
                .scanlines(alpha = 0.25f),
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            Header(games?.size)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        games == null -> PixelText("CARGANDO...", size = 10.sp, color = RetroColors.Muted)
                        else -> Shelves(games, covers, onPlay, onOptions = { game, requester -> options = game to requester })
                    }
                }
                Spacer(Modifier.width(20.dp))
                ConnectPanel(serverFailed, Modifier.width(210.dp).fillMaxHeight())
            }
        }
        options?.let { (game, requester) ->
            val close = {
                options = null
                runCatching { requester.requestFocus() }
                Unit
            }
            GameOptions(
                game = game,
                saves = remember(game) { savesOf(game) },
                onPlay = { close(); onPlay(game) },
                onDelete = { options = null; onDelete(game) },
                onClose = close,
            )
        }
        if (toast != null) {
            PixelText(
                toast,
                size = 10.sp,
                color = RetroColors.Night,
                shadow = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .pixelPanel(fill = RetroColors.Yellow, border = RetroColors.Shadow)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Header(count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelText("RETRO", size = 22.sp, color = RetroColors.Yellow, shadow = RetroColors.Red)
        PixelText("TV", size = 22.sp, color = RetroColors.Cream, shadow = RetroColors.Blue)
        Spacer(Modifier.width(18.dp))
        if (rememberBlink()) PixelText("PRESS START", size = 8.sp, color = RetroColors.Muted, shadow = null)
        Spacer(Modifier.weight(1f))
        if (count != null) {
            PixelText(
                "$count ${if (count == 1) "JUEGO" else "JUEGOS"}",
                size = 9.sp,
                color = RetroColors.Muted,
            )
        }
        Spacer(Modifier.width(22.dp))
        // Firma: gatito pixel art + cocidev.
        PixelCat(pixel = 2.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            PixelText("COCI", size = 7.sp, color = RetroColors.Yellow, shadow = RetroColors.Red)
            Spacer(Modifier.height(3.dp))
            PixelText("DEV", size = 7.sp, color = RetroColors.Cream, shadow = RetroColors.Blue)
        }
    }
}

@Composable
private fun Shelves(
    games: List<Game>,
    covers: Covers,
    onPlay: (Game) -> Unit,
    onOptions: (Game, FocusRequester) -> Unit,
) {
    val shelves = remember(games) { shelvesFor(games) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Se fija la pestaña inicial una vez: si no, al aparecer "RECIENTES" tras jugar, la
    // biblioteca saltaría a esa pestaña.
    LaunchedEffect(shelves) { if (selectedKey == null) selectedKey = shelves.first().key }
    val selected = shelves.firstOrNull { it.key == selectedKey } ?: shelves.first()
    var focusedGame by remember { mutableStateOf<Game?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            items(shelves, key = { it.key }) { shelf ->
                ShelfTab(shelf, selected = shelf.key == selected.key, onFocus = { selectedKey = shelf.key })
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f)) {
            if (selected.games.isEmpty()) {
                EmptyShelf()
            } else {
                GameGrid(selected, covers, onPlay, onOptions, onFocus = { focusedGame = it })
            }
        }
        Footer(focusedGame?.takeIf { it in selected.games })
    }
}

@Composable
private fun ShelfTab(shelf: Shelf, selected: Boolean, onFocus: () -> Unit) {
    RetroFocusable(onClick = onFocus, onFocus = onFocus) { focused ->
        PixelText(
            shelf.label,
            size = 9.sp,
            color = if (selected) RetroColors.Night else shelf.color,
            shadow = null,
            modifier = Modifier
                .pixelPanel(
                    fill = if (selected) shelf.color else RetroColors.Panel,
                    border = if (focused) RetroColors.Yellow else RetroColors.Border,
                    shadow = null,
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun GameGrid(
    shelf: Shelf,
    covers: Covers,
    onPlay: (Game) -> Unit,
    onOptions: (Game, FocusRequester) -> Unit,
    onFocus: (Game) -> Unit,
) {
    val first = remember(shelf.key) { FocusRequester() }
    // Al abrir la app (y si se borra un juego), el foco va al primer juego.
    LaunchedEffect(shelf.games.size) { runCatching { first.requestFocus() } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        items(shelf.games, key = { it.id }) { game ->
            val modifier = if (game == shelf.games.first()) Modifier.focusRequester(first) else Modifier
            GameCard(
                game,
                covers,
                modifier,
                onClick = { onPlay(game) },
                onOptions = { onOptions(game, it) },
                onFocus = { onFocus(game) },
            )
        }
    }
}

@Composable
private fun GameCard(
    game: Game,
    covers: Covers,
    modifier: Modifier,
    onClick: () -> Unit,
    onOptions: (FocusRequester) -> Unit,
    onFocus: () -> Unit,
) {
    val cover by produceState<android.graphics.Bitmap?>(null, game.id) { value = covers.load(game) }
    val accent = Color(game.system.color)
    val requester = remember { FocusRequester() }
    var longPressed by remember { mutableStateOf(false) }

    RetroFocusable(
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when {
                    // ☰ del control remoto (o SELECT del celular): abre la ficha.
                    native.keyCode == KeyEvent.KEYCODE_MENU -> {
                        if (event.type == KeyEventType.KeyUp) onOptions(requester)
                        true
                    }
                    native.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && native.keyCode != KeyEvent.KEYCODE_ENTER -> false
                    // OK mantenido: la primera repetición abre la ficha; el resto y el soltar
                    // se consumen para que no se lance el juego.
                    event.type == KeyEventType.KeyDown && native.repeatCount == 0 -> {
                        longPressed = false
                        false
                    }
                    event.type == KeyEventType.KeyDown && native.repeatCount == 1 -> {
                        longPressed = true
                        onOptions(requester)
                        true
                    }
                    longPressed -> {
                        if (event.type == KeyEventType.KeyUp) longPressed = false
                        true
                    }
                    else -> false
                }
            },
    ) { focused ->
        Column(
            Modifier
                .scale(if (focused) 1.07f else 1f)
                .pixelPanel(
                    fill = if (focused) RetroColors.PanelLight else RetroColors.Panel,
                    border = if (focused) RetroColors.Yellow else RetroColors.Border,
                    px = if (focused) 4.dp else 3.dp,
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        painter = remember(bitmap) {
                            BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.Low)
                        },
                        contentDescription = game.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CartridgeArt(game, accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(game.system.shortName, size = 6.sp, color = accent, shadow = null)
                Spacer(Modifier.width(6.dp))
                PixelText(
                    game.title.uppercase(),
                    size = 6.sp,
                    color = if (focused) RetroColors.Yellow else RetroColors.Cream,
                    shadow = null,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Cartucho pixelado cuando el juego no tiene carátula. */
@Composable
private fun CartridgeArt(game: Game, accent: Color) {
    Column(
        Modifier
            .width(86.dp)
            .fillMaxHeight()
            .pixelPanel(fill = Color(0xFF55536A), border = Color(0xFF26242F), px = 3.dp, shadow = null)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Ranuras de agarre arriba del cartucho.
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF3B3A4A)))
            Spacer(Modifier.height(3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pixelPanel(fill = accent, border = RetroColors.Shadow, px = 2.dp, shadow = null)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            PixelText(
                game.title.uppercase(),
                size = 6.sp,
                color = RetroColors.Cream,
                shadow = RetroColors.Shadow,
                align = TextAlign.Center,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun EmptyShelf() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        PixelText("NO HAY JUEGOS", size = 12.sp, color = RetroColors.Yellow)
        Spacer(Modifier.height(12.dp))
        TermText("Toca \"+ JUEGO\" en el control del celular para subir uno.", size = 20.sp)
    }
}

@Composable
private fun Footer(game: Game?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (game != null) {
            PixelText("▶", size = 9.sp, color = RetroColors.Yellow, shadow = null)
            Spacer(Modifier.width(8.dp))
            TermText("${game.title}  ·  ${game.system.displayName}", size = 20.sp, color = RetroColors.Cream, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        TermText("OK: JUGAR   MANTÉN OK: OPCIONES   ▲ CONSOLAS", size = 18.sp)
    }
}

@Composable
private fun ConnectPanel(serverFailed: Boolean, modifier: Modifier) {
    var ip by remember { mutableStateOf(Network.localIpv4()) }
    // La IP puede cambiar o aparecer después (DHCP); la revisamos cada pocos segundos.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            ip = Network.localIpv4()
        }
    }
    val players by InputHub.players.collectAsState()

    Column(
        modifier.pixelPanel().padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelText("CONECTA TU", size = 9.sp, color = RetroColors.Yellow)
        Spacer(Modifier.height(4.dp))
        PixelText("CONTROL", size = 9.sp, color = RetroColors.Yellow)
        Spacer(Modifier.height(12.dp))
        val currentIp = ip
        when {
            serverFailed -> TermText(
                "No se pudo abrir el puerto 8080. Cierra y vuelve a abrir la app.",
                color = RetroColors.Red,
                align = TextAlign.Center,
            )
            currentIp == null -> TermText("Sin conexión a la red", color = RetroColors.Red)
            else -> {
                val url = Network.controllerUrl(currentIp)
                val qr = remember(url) { BitmapPainter(Network.qrBitmap(url).asImageBitmap(), filterQuality = FilterQuality.None) }
                Box(Modifier.pixelPanel(fill = Color.White, border = RetroColors.Cream).padding(6.dp)) {
                    Image(qr, contentDescription = "Código QR para conectar el control", Modifier.size(124.dp))
                }
                Spacer(Modifier.height(8.dp))
                TermText(url, size = 17.sp, color = RetroColors.Cream)
            }
        }
        Spacer(Modifier.height(12.dp))
        for (slot in 0 until Pad.MAX_PLAYERS) {
            val player = players.firstOrNull { it.slot == slot }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (player != null) RetroColors.Players[slot] else RetroColors.Border),
                )
                Spacer(Modifier.width(8.dp))
                PixelText(
                    "${slot + 1}P",
                    size = 8.sp,
                    color = if (player != null) RetroColors.Players[slot] else RetroColors.Muted,
                    shadow = null,
                )
                Spacer(Modifier.width(8.dp))
                TermText(player?.name ?: "---", size = 17.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        TermText(
            "¿Más juegos? Toca \"+ JUEGO\" en el celular",
            size = 16.sp,
            align = TextAlign.Center,
        )
    }
}

/** Ficha del juego con opción de borrarlo (con confirmación). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun GameOptions(
    game: Game,
    saves: Int,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    BackHandler { if (confirming) confirming = false else onClose() }
    val date = remember(game) {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(game.file.lastModified()))
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xD00F0B1E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .pixelPanel(px = 4.dp)
                .padding(horizontal = 24.dp, vertical = 22.dp)
                // El foco no puede escaparse a la biblioteca de atrás.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            PixelText(game.system.shortName, size = 8.sp, color = Color(game.system.color), shadow = null)
            Spacer(Modifier.height(8.dp))
            PixelText(game.title.uppercase(), size = 13.sp, color = RetroColors.Yellow, shadow = RetroColors.Red, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            TermText("${game.system.displayName}  ·  ${formatSize(game.file.length())}  ·  $date", size = 19.sp)
            TermText(
                when (saves) {
                    0 -> "Sin partidas guardadas"
                    1 -> "1 partida guardada"
                    else -> "$saves partidas guardadas"
                },
                size = 19.sp,
            )
            Spacer(Modifier.height(16.dp))
            if (!confirming) {
                DialogButton("JUGAR", onPlay, first = true)
                DialogButton("BORRAR JUEGO", { confirming = true }, color = RetroColors.Red)
                DialogButton("CANCELAR", onClose)
            } else {
                PixelText("¿BORRAR ESTE JUEGO?", size = 10.sp, color = RetroColors.Red)
                Spacer(Modifier.height(6.dp))
                if (saves > 0) TermText("También se borrarán sus partidas guardadas.", size = 18.sp)
                Spacer(Modifier.height(10.dp))
                // "NO" primero y con el foco: borrar por accidente cuesta caro.
                DialogButton("NO, CONSERVAR", { confirming = false }, first = true)
                DialogButton("SÍ, BORRAR", onDelete, color = RetroColors.Red)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, color: Color = RetroColors.Cream, first: Boolean = false) {
    val requester = remember { FocusRequester() }
    if (first) LaunchedEffect(label) { runCatching { requester.requestFocus() } }
    val blink = rememberBlink(700)
    RetroFocusable(onClick = onClick, modifier = Modifier.fillMaxWidth().focusRequester(requester)) { focused ->
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
            PixelText(label, size = 10.sp, color = if (focused) RetroColors.Yellow else color)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "${(bytes + 1023) / 1024} KB"
}
