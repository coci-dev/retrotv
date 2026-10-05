package com.joel.retrotv.server

import android.content.Context
import android.util.Log
import com.joel.retrotv.input.InputHub
import com.joel.retrotv.input.Seat
import com.joel.retrotv.library.GameLibrary
import com.joel.retrotv.library.GameSystem
import com.joel.retrotv.library.ImportException
import com.joel.retrotv.library.usedCount
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.seconds

/**
 * Servidor HTTP + WebSocket para los controles en el celular. Ver docs/protocol.md.
 */
class ControllerServer(context: Context, private val library: GameLibrary) {

    private val assets = context.applicationContext.assets
    private var server: EmbeddedServer<*, *>? = null

    private val _failed = MutableStateFlow(false)

    /** true si no se pudo abrir el puerto (otra app lo usa, etc.). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            server = createServer().also { it.start(wait = false) }
            _failed.value = false
            Log.i(TAG, "Servidor del control en el puerto $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el puerto $PORT", e)
            server = null
            _failed.value = true
        }
    }

    private fun createServer() = embeddedServer(
        CIO,
        configure = {
            connector {
                port = PORT
                host = "0.0.0.0"
            }
            // Al volver a la app, el puerto puede seguir en TIME_WAIT por las conexiones anteriores.
            reuseAddress = true
        },
    ) {
        install(WebSockets) {
            pingPeriod = 5.seconds
            timeout = 10.seconds
        }
        routing {
            webSocket("/ws") { handlePlayer(this) }
            post("/api/upload") { handleUpload(call) }
            get("/api/systems") {
                val exts = JSONArray(GameSystem.romExtensions.sorted() + "zip")
                call.respondJson(HttpStatusCode.OK, JSONObject().put("extensions", exts))
            }
            get("/api/games") {
                val games = withContext(Dispatchers.IO) {
                    library.scan().map { game ->
                        JSONObject()
                            .put("id", game.id)
                            .put("title", game.title)
                            .put("system", game.system.shortName)
                            .put("systemName", game.system.displayName)
                            .put("size", game.file.length())
                            .put("addedAt", game.file.lastModified())
                            .put("saves", library.slots(game).usedCount())
                    }
                }
                call.respondJson(HttpStatusCode.OK, JSONObject().put("games", JSONArray(games)))
            }
            post("/api/delete") {
                val id = call.request.queryParameters["id"].orEmpty()
                val deleted = withContext(Dispatchers.IO) {
                    library.find(id)?.let { library.delete(it) }
                }
                when (deleted) {
                    true -> call.respondJson(HttpStatusCode.OK, JSONObject().put("ok", true))
                    false -> call.respondJson(HttpStatusCode.InternalServerError, error("No se pudo borrar el juego"))
                    null -> call.respondJson(HttpStatusCode.NotFound, error("Juego no encontrado"))
                }
            }
            get("/") { serveAsset(call, "index.html") }
            get("/{path...}") {
                val path = call.parameters.getAll("path").orEmpty().joinToString("/")
                serveAsset(call, path)
            }
        }
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        val name = call.request.queryParameters["name"].orEmpty()
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > GameLibrary.MAX_UPLOAD_BYTES) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, error("El archivo supera 64 MB"))
            return
        }
        try {
            val game = withContext(Dispatchers.IO) {
                call.receiveChannel().toInputStream().use { library.import(name, it) }
            }
            Log.i(TAG, "Juego subido: ${game.file.name}")
            call.respondJson(
                HttpStatusCode.OK,
                JSONObject().put("ok", true).put("title", game.title).put("system", game.system.shortName),
            )
        } catch (e: ImportException) {
            call.respondJson(HttpStatusCode.BadRequest, error(e.message ?: "Archivo no válido"))
        } catch (e: Exception) {
            Log.e(TAG, "Falló la subida de $name", e)
            call.respondJson(HttpStatusCode.InternalServerError, error("No se pudo guardar el juego"))
        }
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, json: JSONObject) =
        respondText(json.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8), status)

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    private suspend fun handlePlayer(session: io.ktor.server.websocket.DefaultWebSocketServerSession) =
        with(session) {
            var seat: Seat? = null
            try {
                while (true) {
                    // Sin mensajes en 3 s = desconectado (el celular manda keep-alive cada 1 s).
                    val frame = withTimeoutOrNull(3_000) { incoming.receiveCatching().getOrNull() }
                        ?: break
                    if (frame !is Frame.Text) continue
                    val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue

                    when (msg.optString("t")) {
                        "hello" -> {
                            if (seat != null) continue
                            val name = msg.optString("name").take(32).ifBlank { "Celular" }
                            val joined = InputHub.join(name, msg.optString("id").take(64))
                            if (joined == null) {
                                send("""{"t":"full"}""")
                                break
                            }
                            seat = joined
                            send("""{"t":"welcome","player":${joined.slot + 1}}""")
                        }
                        "s" -> seat?.let { InputHub.setMask(it.slot, msg.optInt("b") and 0xFFF) }
                        "ping" -> send("""{"t":"pong","ts":${msg.optDouble("ts", 0.0)}}""")
                        "menu" -> seat?.let { InputHub.menu(it.slot) }
                    }
                }
            } finally {
                seat?.let { InputHub.leave(it) }
            }
        }

    private suspend fun serveAsset(call: io.ktor.server.application.ApplicationCall, path: String) {
        if (path.contains("..")) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val bytes = try {
            assets.open("web/$path").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(bytes, contentTypeFor(path))
    }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.')) {
        "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        "js", "mjs" -> ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "ttf" -> ContentType.parse("font/ttf")
        "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        "webmanifest" -> ContentType.parse("application/manifest+json")
        else -> ContentType.Application.OctetStream
    }

    companion object {
        const val PORT = 8080
        private const val TAG = "ControllerServer"
    }
}
