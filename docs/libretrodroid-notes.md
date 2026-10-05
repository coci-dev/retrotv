# LibretroDroid 0.14.0 notes

Verified against the JitPack artifact (`LibretroDroid-0.14.0.aar`, `-sources.jar`, `javap` on `classes.jar`)
and the GitHub repo at tag `0.14.0` (sample app + `libretrodroid/src/main/cpp/input.cpp`, `core.cpp`).

## Artifact facts

- Coordinates: `com.github.Swordfish90:LibretroDroid:0.14.0`. Repository: `maven { url = uri("https://jitpack.io") }` (plus google/mavenCentral for transitive deps).
- Transitive runtime deps: `org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21`, `androidx.lifecycle:lifecycle-runtime-ktx:2.5.1` (brings `kotlinx-coroutines-android`, which `GLRetroView` uses for `Flow`).
- AAR manifest: `minSdkVersion 21`, `<uses-feature android:glEsVersion="0x00020000" android:required="true"/>`. No permissions.
  (The view picks GLES 3 if `reqGlEsVersion >= 0x30000`, else GLES 2.)
- Bytecode: Java 17 (class major 61), Kotlin metadata 2.0. Use AGP 8.x and Kotlin >= 1.9.
- Native lib `liblibretrodroid.so` ships for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- **No consumer ProGuard rules are shipped.** JNI creates/reads `Variable`, `Controller`, `GLRetroShader`, `ImmersiveMode`, `DetachedVirtualFile` and calls the private `GLRetroView.sendRumbleEvent`. If minifying, add:
  `-keep class com.swordfish.libretrodroid.** { *; }`
- Native state is a process-wide singleton (`LibretroDroid` static natives), so only one `GLRetroView` can be alive at a time.

## Public API (package `com.swordfish.libretrodroid`)

```kotlin
class GLRetroViewData(context: Context) {
    var coreFilePath: String? = null
    var gameFilePath: String? = null          // checked first
    var gameFileBytes: ByteArray? = null      // used if gameFilePath == null
    var gameVirtualFiles: List<VirtualFile> = listOf()
    var systemDirectory: String = context.filesDir.absolutePath
    var savesDirectory: String = context.filesDir.absolutePath
    var variables: Array<Variable> = arrayOf()
    var saveRAMState: ByteArray? = null       // SRAM restored right after game load
    var shader: ShaderConfig = ShaderConfig.Default
    var viewportAlignment = ViewportAlignment.CENTER
    var rumbleEventsEnabled: Boolean = true
    var preferLowLatencyAudio: Boolean = true
    var skipDuplicateFrames: Boolean = false
    var enableMicrophone: Boolean = false
    var immersiveMode: ImmersiveMode? = null
}

enum class ViewportAlignment(val value: Int) { CENTER(0), TOP(1), BOTTOM(2) }
data class Variable(val key: String? = null, val value: String? = null, val description: String? = null)
data class Controller(val id: Int = -1, val description: String? = null)
data class RumbleEvent(val port: Int, val strengthWeak: Float, val strengthStrong: Float)
data class VirtualFile(val virtualPath: String, val fileDescriptor: ParcelFileDescriptor)
data class ImmersiveMode(val downscaledWidth: Int = 8, val downscaledHeight: Int = 8, val blurMaskSize: Int = 5,
                         val blurBrightness: Float = 0.5F, val blurSkipUpdate: Int = 2, val blendFactor: Float = 0.1F)
class RetroException(val errorCode: Int) : RuntimeException

sealed interface ShaderConfig {
    object Default : ShaderConfig   // bilinear
    object CRT : ShaderConfig
    object LCD : ShaderConfig
    object Sharp : ShaderConfig     // unfiltered / nearest-ish
    data class CUT(...) : ShaderConfig   // upscalers; all params have defaults
    data class CUT2(...) : ShaderConfig
    data class CUT3(...) : ShaderConfig
}

class GLRetroView(context: Context, data: GLRetroViewData) : GLSurfaceView(context), LifecycleObserver {
    var audioEnabled: Boolean            // default true
    var frameSpeed: Int                  // 1 = normal; >1 fast-forward
    var shader: ShaderConfig
    var viewport: RectF                  // default RectF(0f, 0f, 1f, 1f)
    var viewportAlignment: ViewportAlignment

    @OnLifecycleEvent(ON_CREATE)  fun onCreate(lifecycleOwner: LifecycleOwner)  // calls native create (loads core)
    @OnLifecycleEvent(ON_DESTROY) fun onDestroy()                             // native destroy
    // ON_RESUME/ON_PAUSE are handled by an inner observer registered after the GL surface is created
    // (it calls LibretroDroid.resume()/pause() + GLSurfaceView.onResume()/onPause()). No public pause();
    // pausing = the Activity lifecycle pausing (or frameSpeed/audioEnabled tweaks).

    fun sendKeyEvent(action: Int, keyCode: Int, port: Int = 0)
    fun sendMotionEvent(source: Int, xAxis: Float, yAxis: Float, port: Int = 0)

    fun serializeState(useEmulationThread: Boolean = true): ByteArray
    fun unserializeState(data: ByteArray, useEmulationThread: Boolean = true): Boolean
    fun serializeSRAM(useEmulationThread: Boolean = true): ByteArray
    fun unserializeSRAM(data: ByteArray, useEmulationThread: Boolean = true): Boolean
    fun reset(useEmulationThread: Boolean = true)
    fun setCheat(index: Int, enable: Boolean, code: String, useEmulationThread: Boolean = true)

    fun getGLRetroEvents(): Flow<GLRetroView.GLRetroEvents>   // FrameRendered, SurfaceCreated (objects)
    fun getGLRetroErrors(): Flow<Int>                          // GLRetroView.ERROR_* codes
    fun getRumbleEvents(): Flow<RumbleEvent>

    fun getControllers(): Array<Array<Controller>>
    fun setControllerType(port: Int, type: Int)
    fun getVariables(): Array<Variable>
    fun updateVariables(vararg variables: Variable)
    fun getAvailableDisks(useEmulationThread: Boolean = true): Int
    fun getCurrentDisk(useEmulationThread: Boolean = true): Int
    fun changeDisk(index: Int, useEmulationThread: Boolean = true)

    sealed class GLRetroEvents { object FrameRendered; object SurfaceCreated }
    companion object {
        const val MOTION_SOURCE_DPAD = 0; MOTION_SOURCE_ANALOG_LEFT = 1
        const val MOTION_SOURCE_ANALOG_RIGHT = 2; MOTION_SOURCE_POINTER = 3
        const val ERROR_LOAD_LIBRARY = 0; ERROR_LOAD_GAME = 1; ERROR_GL_NOT_COMPATIBLE = 2
        const val ERROR_SERIALIZATION = 3; ERROR_CHEAT = 4; ERROR_GENERIC = -1
    }
}
```

`serializeState/SRAM` with `useEmulationThread = true` post to the GL thread and **block the caller** on a
latch until done: call them from `Dispatchers.IO` (or at least not in a tight UI path).

## Core loading

Native side does `dlopen(coreFilePath, RTLD_LOCAL | RTLD_LAZY)`. The sample sets
`coreFilePath = "libmgba_libretro_android.so"` (bare file name) with the .so in `app/src/main/jniLibs/<abi>/`;
the app's linker namespace resolves it from nativeLibraryDir or directly from the APK.

- Bare name: `coreFilePath = "libfceumm_libretro_android.so"` works whether or not libs are extracted.
- Absolute path (Lemuroid style): `"${applicationInfo.nativeLibraryDir}/libfceumm_libretro_android.so"` only exists
  if libs are extracted -> set `packaging { jniLibs { useLegacyPackaging = true } }` (extractNativeLibs=true).
- Package manager only extracts files named `lib*.so`, hence the `lib` prefix on our core files.
- Recommend `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }`. A 32-bit-only userspace (likely on the
  Mi TV A2) installs/loads `armeabi-v7a`; core and liblibretrodroid must match ABI, which they do for both.

## Input

`sendKeyEvent(action, keyCode, port)` forwards Android `KeyEvent.ACTION_DOWN/ACTION_UP` + keycode to native
`Input::onKeyEvent`, which maps (no A/B swap at this level):

| Android keycode           | RetroPad id    | NES (FCEUmm) |
|---------------------------|----------------|--------------|
| KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT | UP/DOWN/LEFT/RIGHT | D-pad |
| KEYCODE_DPAD_UP_LEFT etc. (diagonals) | diagonal (both dirs) | D-pad |
| KEYCODE_BUTTON_A          | A              | A |
| KEYCODE_BUTTON_B          | B              | B |
| KEYCODE_BUTTON_X          | X              | (turbo A if enabled) |
| KEYCODE_BUTTON_Y          | Y              | (turbo B if enabled) |
| KEYCODE_BUTTON_START      | START          | Start |
| KEYCODE_BUTTON_SELECT     | SELECT         | Select |
| KEYCODE_BUTTON_L1 / R1    | L / R          | - |
| KEYCODE_BUTTON_L2 / R2    | L2 / R2        | - |
| KEYCODE_BUTTON_THUMBL / THUMBR | L3 / R3   | - |
| anything else (e.g. DPAD_CENTER, ENTER, BACK) | ignored | - |

- Ports: valid 0..3 (native has 4 pads; **no bounds check in onKeyEvent** -> never pass port >= 4).
- RetroPad layout: A = east/right, B = south/bottom. Android/Xbox-style pads report the south button as
  `KEYCODE_BUTTON_A`. `GLRetroView.onKeyDown/onKeyUp` itself swaps A<->B and X<->Y (GamepadsManager) and uses
  `port = event.device.controllerNumber - 1`, but only when the view has focus. If you forward keys yourself
  (e.g. in `Activity.dispatchKeyEvent`), apply the same swap and consume the event so the view does not double-send.
- TV remote: `KEYCODE_DPAD_CENTER`/`KEYCODE_ENTER` are not mapped; translate them yourself (e.g. to `KEYCODE_BUTTON_B`
  -> RetroPad A ... choose your mapping) before calling `sendKeyEvent`.
- Many gamepads send the d-pad as HAT axes: forward `MotionEvent.AXIS_HAT_X/Y` via
  `sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)` (values rounded to -1/0/1); analog sticks via
  `MOTION_SOURCE_ANALOG_LEFT` (AXIS_X/AXIS_Y) and `MOTION_SOURCE_ANALOG_RIGHT` (AXIS_Z/AXIS_RZ).
- Virtual (phone) input: just call `sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, port)` etc.

## Minimal usage

```kotlin
class GameActivity : AppCompatActivity() {   // any LifecycleOwner Activity
    private lateinit var retroView: GLRetroView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).also { setContentView(it) }

        val rom = File(filesDir, "roms/table_tennis_v1.0.nes")   // or gameFileBytes = assets.open(...).readBytes()
        val sramFile = File(filesDir, "saves/${rom.nameWithoutExtension}.srm")

        val data = GLRetroViewData(this).apply {
            coreFilePath = "libfceumm_libretro_android.so"
            gameFilePath = rom.absolutePath
            systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
            savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
            saveRAMState = sramFile.takeIf { it.exists() }?.readBytes()
            shader = ShaderConfig.Sharp
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)            // REQUIRED: triggers native create/destroy + resume/pause
        container.addView(retroView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code -> /* show error, finish() */ }
        }
    }

    // Port 0..3; swap A/B, X/Y to RetroPad positions.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val port = ((event.device?.controllerNumber ?: 1) - 1).coerceIn(0, 3)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> event.keyCode
        }
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            retroView.sendKeyEvent(event.action, key, port)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val port = ((event.device?.controllerNumber ?: 1) - 1).coerceIn(0, 3)
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD,
            event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), port)
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
            event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), port)
        return true
    }

    // Save / load state (blocking on the GL thread -> run off the main thread)
    fun saveState(slot: File) = lifecycleScope.launch(Dispatchers.IO) {
        slot.writeBytes(retroView.serializeState())
    }
    fun loadState(slot: File) = lifecycleScope.launch(Dispatchers.IO) {
        if (slot.exists()) retroView.unserializeState(slot.readBytes())
    }

    override fun onPause() {
        // Persist battery SRAM (NES games with battery saves); empty array if the game has none.
        val sram = retroView.serializeSRAM()
        if (sram.isNotEmpty()) File(filesDir, "saves/table_tennis_v1.0.srm").writeBytes(sram)
        super.onPause()
    }
}
```

Notes: wait for `GLRetroEvents.SurfaceCreated` (or the first `FrameRendered`) before loading a state on
startup — the game is loaded on the GL thread in `onSurfaceCreated`.

## Bundled test ROM

- **8-Bit Table Tennis** v1.0 — homebrew 2-player NES game (mapper 0/NROM, 32 KB PRG, 8 KB CHR).
- Author: Michael Billington (mike42), based on Brad Smith's NES-ca65-example ("can be freely redistributed,
  modified or reused in any way").
- License: MIT ("Copyright (c) 2020 Michael Billington"). Ship the MIT notice with the app (e.g. an About/licenses screen).
- Source: https://github.com/mike42/8bit-table-tennis — ROM: https://github.com/mike42/8bit-table-tennis/releases/download/v1.0/table_tennis_v1.0.nes
- File: `tv-app/src/main/assets/roms/table_tennis_v1.0.nes`, 40,976 bytes,
  SHA-256 `05122457672F95FCF2EE518C3F37E506617725C2D031100B1B1C8B7785068EA3`.
- Controls: START to begin, UP/DOWN to move, A to serve; player 1 = port 0, player 2 = port 1.

## Core

- FCEUmm from libretro buildbot nightly (`https://buildbot.libretro.com/nightly/android/latest/<abi>/fceumm_libretro_android.so.zip`), GPLv2.
- `tv-app/src/main/jniLibs/armeabi-v7a/libfceumm_libretro_android.so` (1,530,204 bytes, ELF32 ARM)
- `tv-app/src/main/jniLibs/arm64-v8a/libfceumm_libretro_android.so` (2,878,064 bytes, ELF64 AArch64)
- LibretroDroid is GPLv3: the app as a whole must be distributed under GPLv3-compatible terms.
