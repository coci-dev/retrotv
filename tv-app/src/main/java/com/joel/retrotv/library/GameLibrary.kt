package com.joel.retrotv.library

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

data class Game(val file: File, val system: GameSystem, val lastPlayed: Long = 0) {
    /** Nombre del archivo sin extensión; así se llaman las carátulas de libretro. */
    val baseName: String = file.nameWithoutExtension

    /** Título para mostrar: sin etiquetas tipo "(USA)" o "[!]". */
    val title: String = baseName
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace('_', ' ')
        .trim()
        .ifEmpty { baseName }

    val id: String = "${system.name}-${file.name}"
}

/** Ranuras usadas. */
fun List<SaveSlot>.usedCount() = count { it.file.exists() }

/** Ranura de guardado: [index] 1..[GameLibrary.SLOTS]. */
data class SaveSlot(val index: Int, val file: File) {
    val savedAt: Long? get() = file.takeIf { it.exists() }?.lastModified()
}

class ImportException(message: String) : Exception(message)

/**
 * Busca ROMs en:
 * - ROMs incluidas en `assets/roms` (se copian a almacenamiento interno la primera vez).
 * - `/sdcard/Android/data/com.joel.retrotv/files/roms`: las subidas desde el celular y las
 *   copiadas con `adb push`.
 */
class GameLibrary(private val context: Context) {

    val userRomsDir: File = (context.getExternalFilesDir("roms") ?: File(context.filesDir, "user-roms"))
        .apply { mkdirs() }
    private val bundledDir = File(context.filesDir, "roms").apply { mkdirs() }

    val savesDir = File(context.filesDir, "saves").apply { mkdirs() }
    val statesDir = File(context.filesDir, "states").apply { mkdirs() }
    val systemDir = File(context.filesDir, "system").apply { mkdirs() }

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)

    /** Cambia cada vez que se agrega un juego; la UI vuelve a escanear. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _imports = MutableSharedFlow<Game>(extraBufferCapacity = 8)

    /** Juegos recién subidos desde un celular (para avisar en pantalla). */
    val imports: SharedFlow<Game> = _imports.asSharedFlow()

    fun scan(): List<Game> {
        copyBundledRoms()
        return listOf(bundledDir, userRomsDir)
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile }
            .mapNotNull { file ->
                GameSystem.forFile(file)?.let { system ->
                    Game(file, system, prefs.getLong("played:${system.name}-${file.name}", 0))
                }
            }
            .distinctBy { it.file.name }
            .sortedBy { it.title.lowercase() }
    }

    fun find(id: String): Game? = scan().firstOrNull { it.id == id }

    /** Borra la ROM y sus partidas (ranuras y SRAM). */
    fun delete(game: Game): Boolean {
        val removed = game.file.delete() || !game.file.exists()
        if (!removed) return false
        slots(game).forEach { it.file.delete() }
        sramFile(game).delete()
        val editor = prefs.edit().remove("played:${game.id}")
        // Las ROMs incluidas en la app se volverían a copiar: se recuerdan como borradas.
        if (game.file.parentFile == bundledDir) editor.putBoolean("hidden:${game.file.name}", true)
        editor.apply()
        _version.value++
        return true
    }

    fun markPlayed(game: Game) {
        prefs.edit().putLong("played:${game.id}", System.currentTimeMillis()).apply()
        _version.value++
    }

    fun sramFile(game: Game) = File(savesDir, "${game.id}.srm")

    fun slots(game: Game): List<SaveSlot> =
        (1..SLOTS).map { SaveSlot(it, File(statesDir, "${game.id}.slot$it.state")) }

    /**
     * Guarda un juego subido. Acepta ROMs sueltas o un .zip con una ROM adentro.
     * Lee como máximo [MAX_UPLOAD_BYTES].
     */
    @Throws(ImportException::class, IOException::class)
    fun import(fileName: String, input: InputStream): Game {
        val name = sanitize(File(fileName).name)
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "zip" -> importZip(input)
            GameSystem.forExtension(ext) != null -> writeRom(name, input)
            else -> throw ImportException("Formato no soportado: .$ext")
        }.also {
            _version.value++
            _imports.tryEmit(it)
        }
    }

    private fun importZip(input: InputStream): Game {
        ZipInputStream(LimitedInputStream(input, MAX_UPLOAD_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val entryName = sanitize(File(entry.name).name)
                if (GameSystem.forExtension(entryName.substringAfterLast('.', "")) != null) {
                    return writeRom(entryName, zip)
                }
            }
        }
        throw ImportException("El .zip no contiene ningún juego compatible")
    }

    private fun writeRom(name: String, input: InputStream): Game {
        // Si alguien vuelve a subir un juego incluido que había borrado, deja de estar oculto.
        prefs.edit().remove("hidden:$name").apply()
        val target = File(userRomsDir, name)
        val tmp = File(userRomsDir, "$name.part")
        try {
            tmp.outputStream().use { LimitedInputStream(input, MAX_UPLOAD_BYTES).copyTo(it) }
            if (tmp.length() == 0L) throw ImportException("El archivo está vacío")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("No se pudo guardar $name")
        } finally {
            tmp.delete()
        }
        return Game(target, GameSystem.forFile(target)!!)
    }

    private fun copyBundledRoms() {
        val names = context.assets.list("roms").orEmpty()
        for (name in names) {
            val target = File(bundledDir, name)
            if (target.exists() || prefs.getBoolean("hidden:$name", false)) continue
            // Copia a .tmp y renombra: una copia interrumpida no deja una ROM truncada.
            val tmp = File(bundledDir, "$name.tmp")
            context.assets.open("roms/$name").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(target)
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120).ifEmpty { "juego" }

    companion object {
        const val SLOTS = 4
        const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024
    }
}

/** Corta la lectura con [ImportException] si se pasa de [limit] bytes. */
private class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) check(n)
        return n
    }

    private fun check(n: Int) {
        count += n
        if (count > limit) throw ImportException("El archivo supera ${limit / (1024 * 1024)} MB")
    }
}
