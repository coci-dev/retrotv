package com.joel.retrotv.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Carátulas desde thumbnails.libretro.com (coinciden con los nombres No-Intro de las ROMs).
 * Se guardan en disco; los juegos sin carátula se recuerdan por una semana para no reintentar.
 */
class Covers(context: Context) {

    private val dir = File(context.cacheDir, "covers").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val network = Mutex()

    suspend fun load(game: Game): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(game.id)?.let { return@withContext it }
        val file = File(dir, "${game.id}.png")
        val miss = File(dir, "${game.id}.none")
        if (!file.exists()) {
            if (miss.exists() && miss.lastModified() > System.currentTimeMillis() - MISS_TTL) {
                return@withContext null
            }
            // De a una descarga a la vez: la TV tiene poca CPU y RAM.
            val ok = network.withLock { download(game, file) }
            if (!ok) {
                miss.writeText("")
                return@withContext null
            }
        }
        decode(file)?.also { memory.put(game.id, it) }
    }

    private fun download(game: Game, target: File): Boolean {
        val name = URLEncoder.encode(thumbnailName(game.baseName), "UTF-8").replace("+", "%20")
        val folder = URLEncoder.encode(game.system.thumbnailDir, "UTF-8").replace("+", "%20")
        val url = URL("https://thumbnails.libretro.com/$folder/Named_Boxarts/$name.png")
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            try {
                if (conn.responseCode != 200) return false
                val tmp = File(target.path + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Sin carátula para ${game.baseName}: ${e.message}")
            false
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    companion object {
        private const val TAG = "Covers"
        private const val TARGET_WIDTH = 256
        private val MISS_TTL = TimeUnit.DAYS.toMillis(7)

        /** libretro reemplaza estos caracteres por "_" en los nombres de archivo. */
        fun thumbnailName(name: String) = name.replace(Regex("""[&*/:`<>?\\|"]"""), "_")
    }
}
