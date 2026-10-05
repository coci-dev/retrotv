package com.joel.retrotv.library

import java.io.File

/**
 * Consolas soportadas y el core de libretro que las emula. El .so de cada core vive en
 * `jniLibs/<abi>/lib<core>_libretro_android.so`.
 *
 * [thumbnailDir] es la carpeta de carátulas en thumbnails.libretro.com.
 */
enum class GameSystem(
    val displayName: String,
    val shortName: String,
    private val core: String,
    val extensions: Set<String>,
    val thumbnailDir: String,
    /** Color de acento en la UI (ARGB). */
    val color: Long,
) {
    NES(
        "Nintendo", "NES", "fceumm", setOf("nes"),
        "Nintendo - Nintendo Entertainment System", 0xFFE4404A,
    ),
    SNES(
        // snes9x2010: más liviano que snes9x, importante en CPUs ARM de 32 bits de las TVs.
        "Super Nintendo", "SNES", "snes9x2010", setOf("sfc", "smc"),
        "Nintendo - Super Nintendo Entertainment System", 0xFF8C6BE8,
    ),
    GB(
        "Game Boy", "GB", "gambatte", setOf("gb"),
        "Nintendo - Game Boy", 0xFF9BBC0F,
    ),
    GBC(
        "Game Boy Color", "GBC", "gambatte", setOf("gbc"),
        "Nintendo - Game Boy Color", 0xFF2FB8C4,
    ),
    GBA(
        "Game Boy Advance", "GBA", "mgba", setOf("gba"),
        "Nintendo - Game Boy Advance", 0xFF5B5BD6,
    ),
    MD(
        "Mega Drive", "MD", "genesis_plus_gx", setOf("md", "gen", "smd"),
        "Sega - Mega Drive - Genesis", 0xFF3A7BF2,
    ),
    SMS(
        "Master System", "SMS", "genesis_plus_gx", setOf("sms"),
        "Sega - Master System - Mark III", 0xFFE8524A,
    ),
    GG(
        "Game Gear", "GG", "genesis_plus_gx", setOf("gg"),
        "Sega - Game Gear", 0xFF4CC26B,
    ),
    ;

    /** LibretroDroid hace dlopen de este nombre. */
    val coreFileName: String get() = "lib${core}_libretro_android.so"

    companion object {
        val romExtensions: Set<String> = entries.flatMap { it.extensions }.toSet()

        fun forExtension(ext: String): GameSystem? =
            ext.lowercase().let { e -> entries.firstOrNull { e in it.extensions } }

        fun forFile(file: File): GameSystem? = forExtension(file.extension)
    }
}
