# Credits and licenses

## Bundled ROMs (`tv-app/src/main/assets/roms/`)

| File | Title | System | Author | License | Source |
|---|---|---|---|---|---|
| `table_tennis_v1.0.nes` | Table Tennis | NES | Michael Billington | MIT | https://github.com/mike42/8bit-table-tennis |
| `Thwaite.nes` | Thwaite | NES | Damian Yerrick | GPL-3.0-or-later (some library files under a zlib-like license) | https://github.com/pinobatch/thwaite-nes (ROM from https://github.com/pinobatch/nes-homebrew-db, `entries/thwaite/thwaite128.nes`) |
| `Breakout.sfc` | Breakout (PVSnesLib example) | SNES | alekmaul, based on the SNES SDK example by Ulrich Hecht | MIT (PVSnesLib) | https://github.com/alekmaul/pvsneslib (`snes-examples/systems/games/breakout`, binary from the 4.6.0 release) |
| `Tobu Tobu Girl.gb` | Tobu Tobu Girl | Game Boy | Tangram Games (Simon Larsen and others) | Code: MIT. Assets: CC BY 4.0 | https://github.com/SimonLarsen/tobutobugirl, https://tangramgames.itch.io/tobutobugirl (ROM from https://github.com/gbdev/database, `entries/tobutobugirl/tobu.gb`) |
| `The Hat Chooses the Wizard.gba` | The Hat Chooses the Wizard | Game Boy Advance | Corwin Kuiper and Gwilym Inzani (Kuiper); music "Sylvan Waltz" by Otto Halmén | MPL-2.0 | https://github.com/agbrs/agb/tree/master/examples/the-hat-chooses-the-wizard, https://lostimmortal.itch.io/the-hat-chooses-the-wizard (ROM from https://github.com/gbadev-org/games) |
| `KleleAtoms.gen` | KłełeAtoms MD (v1.2.1) | Mega Drive / Genesis | Nightwolf-47 (title art by GreffMASTER) | MIT | https://github.com/Nightwolf-47/KleleAtoms-MD |

## Fonts (`tv-app/src/main/res/font/`)

| File | Font | Author | License | Source |
|---|---|---|---|---|
| `press_start_2p.ttf` | Press Start 2P | CodeMan38 | SIL Open Font License 1.1 | https://github.com/google/fonts/tree/main/ofl/pressstart2p |
| `vt323.ttf` | VT323 | Peter Hull | SIL Open Font License 1.1 | https://github.com/google/fonts/tree/main/ofl/vt323 |

## Libretro cores (`tv-app/src/main/jniLibs/<abi>/`)

Binaries from the libretro buildbot (https://buildbot.libretro.com/nightly/android/latest/).

| File | Core | Systems | License | Source |
|---|---|---|---|---|
| `libfceumm_libretro_android.so` | FCEUmm | NES / Famicom | GPL-2.0 | https://github.com/libretro/libretro-fceumm |
| `libsnes9x2010_libretro_android.so` | Snes9x 2010 | SNES | Snes9x license (non-commercial) | https://github.com/libretro/snes9x2010 |
| `libgambatte_libretro_android.so` | Gambatte | Game Boy / Game Boy Color | GPL-2.0 | https://github.com/libretro/gambatte-libretro |
| `libmgba_libretro_android.so` | mGBA | Game Boy Advance | MPL-2.0 | https://github.com/libretro/mgba |
| `libgenesis_plus_gx_libretro_android.so` | Genesis Plus GX | Mega Drive / Genesis, Master System, Game Gear, SG-1000, Sega CD | Genesis Plus GX license (non-commercial) | https://github.com/libretro/Genesis-Plus-GX |

Frontend library: LibretroDroid 0.14.0 (Filippo Scognamiglio), GPL-3.0, https://github.com/Swordfish90/LibretroDroid
