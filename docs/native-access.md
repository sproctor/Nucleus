# Nucleus Native Access

Every now and then, no runtime library covers your exact native API need. Nucleus handles the common cases with JNI — but when you need something specific (a platform API, a custom algorithm, a C library), the usual path involves writing JNI glue in C, building a `.so`/`.dylib`/`.dll`, bundling it, and wiring it up from Kotlin. That's a lot of friction for what should be a simple call.

**Nucleus Native Access** removes that friction. Write your native logic in **Kotlin/Native**, and the plugin generates the FFM bridge automatically. No C, no build scripts, no manual JNI plumbing — just Kotlin on both sides.

!!! note "FFM, not JNI"
    Nucleus's built-in runtime libraries (decorated windows, dark mode, notifications…) use **JNI** for broad compatibility. Nucleus Native Access uses the **Foreign Function & Memory (FFM) API** (JEP 454, stable since JDK 22). Both are valid approaches, but FFM lets you write the native side in pure Kotlin rather than C.

---

## How It Works

```
Your Kotlin/Native code
        │
        ▼
  [ Gradle plugin ]
        │  analyzes sources via Kotlin PSI
        │  generates @CName bridge functions (native side)
        │  generates FFM MethodHandle proxies (JVM side)
        │  compiles → .so / .dylib / .dll
        │  bundles into JAR under kne/native/{os}-{arch}/
        ▼
Your JVM code calls it like normal Kotlin
```

The generated JVM proxies have **the exact same API** as your native classes — same names, same types, same method signatures. No wrapper types, no casting, no boilerplate.

---

## Setup

!!! note "Separate versioning"
    Nucleus Native Access is versioned independently from Nucleus. Check the latest version on the [NucleusNativeAccess repository](https://github.com/kdroidFilter/NucleusNativeAccess).

Add the plugin to your Kotlin Multiplatform module:

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("io.github.kdroidfilter.nucleusnativeaccess") version "<version>" // see github.com/kdroidFilter/NucleusNativeAccess
}

kotlin {
    jvmToolchain(25) // FFM requires JDK 22+; JDK 25 recommended

    macosArm64()     // or macosX64(), linuxX64(), mingwX64()
    jvm()
}

kotlinNativeExport {
    nativeLibName = "mylib"
    nativePackage = "com.example.mylib"
}
```

That's the entire configuration. The plugin handles compilation, bundling, and loading automatically.

!!! warning "JDK requirement"
    FFM is stable from **JDK 22+**. JDK 25 is recommended. When running tests or the app, the JVM arg `--enable-native-access=ALL-UNNAMED` is required — the plugin adds it automatically for tests.

### Using with Compose Desktop

The Compose compiler plugin doesn't support arbitrary Kotlin/Native targets (e.g. `linuxX64`, `mingwX64`) used for FFM bridges. **Put your native code in a separate Gradle module** without the Compose compiler plugin:

```
my-app/
├── native/              ← Kotlin/Native + nucleusnativeaccess (no Compose)
│   └── build.gradle.kts
├── app/                 ← Compose Desktop + Nucleus, depends on :native
│   └── build.gradle.kts
└── settings.gradle.kts
```

**`:native/build.gradle.kts`**:

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.kdroidfilter.nucleusnativeaccess") version "<version>"
}

kotlin {
    jvmToolchain(25)
    linuxX64()  // or macosArm64(), mingwX64()
    jvm()
}

kotlinNativeExport {
    nativeLibName = "mylib"
    nativePackage = "com.example.mylib"
}
```

**`:app/build.gradle.kts`**:

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.kdroidfilter.nucleus")
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":native"))
            }
        }
    }
}

nucleus.application {
    mainClass = "com.example.MainKt"
    jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
}
```


---

## Example: Take a Screenshot on macOS

Here's a real-world example: capturing the screen using macOS's CoreGraphics API. This is a platform API with no JVM equivalent — the kind of thing that would normally require JNI C glue.

**Native side** (`src/nativeMain/kotlin/com/example/screen/SystemDesktop.kt`):

```kotlin
// suspend — runs off the main thread, returns PNG bytes
actual suspend fun captureScreen(): ByteArray = memScoped {
    if (!CGPreflightScreenCaptureAccess()) {
        CGRequestScreenCaptureAccess()
        return@memScoped ByteArray(0)
    }

    val rect = alloc<CGRect>().apply {
        origin.x = CGRectInfinite.origin.x
        origin.y = CGRectInfinite.origin.y
        size.width = CGRectInfinite.size.width
        size.height = CGRectInfinite.size.height
    }
    val cgImage = CGWindowListCreateImage(
        rect.readValue(),
        kCGWindowListOptionOnScreenOnly,
        kCGNullWindowID,
        kCGWindowImageDefault,
    ) ?: return@memScoped ByteArray(0)

    // Encode as PNG — NSBitmapImageRep handles all the pixel format details
    val bitmapRep = NSBitmapImageRep(cGImage = cgImage)
    CGImageRelease(cgImage)

    val pngData = bitmapRep.representationUsingType(
        NSBitmapImageFileTypePNG,
        properties = emptyMap<Any?, Any>(),
    ) ?: return@memScoped ByteArray(0)

    ByteArray(pngData.length.toInt()) { i ->
        (pngData.bytes!!.reinterpret<ByteVar>() + i)!!.pointed.value
    }
}
```

**JVM + Compose side** — the plugin generates the proxy, you just use it:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.screen.SystemDesktop
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ScreenshotViewer() {
    val desktop = remember { SystemDesktop() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = {
                capturing = true
                scope.launch {
                    val bytes = desktop.captureScreen()   // suspend — UI never blocks
                    if (bytes.isNotEmpty()) {
                        bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    }
                    capturing = false
                }
            },
            enabled = !capturing,
        ) {
            Text(if (capturing) "Capturing…" else "Capture Screen")
        }

        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Screenshot",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

**No C. No JNI headers. No build scripts. No `System.loadLibrary` call.** The `.dylib` is compiled by the plugin, bundled in the JAR, and extracted automatically at runtime. The `suspend` on the native side maps transparently to a coroutine on the JVM — the UI stays responsive while CoreGraphics does the work.

!!! tip "Full working example"
    The [systeminfo example](https://github.com/kdroidFilter/NucleusNativeAccess/tree/main/examples/systeminfo) in the NucleusNativeAccess repo implements this pattern for all three platforms (CoreGraphics on macOS, XDG ScreenCast + PipeWire on Linux, GDI on Windows), plus native notifications, a system tray menu, and real-time memory updates via `Flow`.

The same pattern works for any other platform API:

=== "macOS"
    ```kotlin
    // Access NSWorkspace, IOKit, CoreBluetooth, AVFoundation, Metal…
    import platform.AppKit.*
    import platform.IOKit.*
    ```

=== "Windows"
    ```kotlin
    // Access Win32, WinRT, DirectX, COM interfaces…
    import platform.windows.*
    ```

=== "Linux"
    ```kotlin
    // Access POSIX, D-Bus, GTK, libnotify…
    import platform.posix.*
    import platform.linux.*
    ```

---

## Using Top-Level Functions

You don't have to wrap everything in a class. Top-level functions are grouped into a singleton `object` named after `nativeLibName` (first letter uppercased):

```kotlin
// build.gradle.kts
kotlinNativeExport {
    nativeLibName = "utils"          // → object Utils { … }
    nativePackage = "com.example.utils"
}
```

```kotlin
// nativeMain — top-level function
package com.example.utils

fun currentProcessId(): Int = platform.posix.getpid()
```

```kotlin
// jvmMain — generated object
import com.example.utils.Utils

val pid = Utils.currentProcessId()
```

---

## Supported Types

| Type | As param | As return | As property | Notes |
|------|----------|-----------|-------------|-------|
| `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short` | ✅ | ✅ | ✅ | Direct pass-through |
| `String` | ✅ | ✅ | ✅ | UTF-8 output-buffer pattern |
| `ByteArray` | ✅ | ✅ | — | Pointer + size; suspend, callbacks, DC fields, collections |
| `enum class` | ✅ | ✅ | ✅ | Ordinal mapping |
| `data class` | ✅ | ✅ | — | Fields: primitives, String, ByteArray, Enum, Object, nested DC, List, Set, Map |
| `Object` (class instances) | ✅ | ✅ | — | Opaque `StableRef` handle, lifecycle tracked |
| Nested classes | ✅ | ✅ | ✅ | Exported as `Outer_Inner`, up to 3+ nesting levels |
| `T?` (nullable) | ✅ | ✅ | ✅ | Sentinel-based null encoding |
| `List<T>`, `Set<T>` | ✅ | ✅ | ✅ | All element types incl. DataClass, ByteArray, nested collections |
| `Map<K, V>` | ✅ | ✅ | ✅ | Parallel key + value arrays |
| `List<List<T>>` | ✅ | ✅ | — | Nested collections via StableRef handles |
| `(T) -> R` (lambda) | ✅ | ✅ | — | FFM upcall/downcall stubs; nullable `((T) -> R)?` supported |
| `suspend fun` | — | ✅ | — | All return types: primitives, String, ByteArray, DataClass, List, Set, Map |
| `Flow<T>` | — | ✅ | — | All element types: primitives, String, ByteArray, DataClass, List, Set, Map |

### What belongs in commonMain, not nativeMain

These constructs can't cross the FFM boundary and should live in shared KMP code:

- Interfaces, abstract/open classes, sealed classes
- Inheritance hierarchies
- Generic class definitions

---

## Object Lifecycle

Generated proxy classes implement `AutoCloseable`. Native memory is freed on `close()`, or automatically when garbage collected (via Java `Cleaner`):

```kotlin
// Preferred — explicit, deterministic
ScreenCapture().use { capture ->
    val bytes = capture.captureScreen()
    // ...
}

// Also valid — Cleaner will release when GC runs
val capture = ScreenCapture()
val bytes = capture.captureScreen()
```

---

## Coroutines and Flows

Suspend functions and `Flow` are transparent — no callbacks, no `CompletableFuture`, just coroutines on both sides:

```kotlin
// nativeMain
suspend fun fetchData(query: String): String {
    delay(100)
    return "result: $query"
}

fun eventStream(max: Int): Flow<Int> = flow {
    for (i in 1..max) { delay(10); emit(i) }
}
```

```kotlin
// jvmMain — identical API
val result = MyLib.fetchData("hello")      // suspends, doesn't block

MyLib.eventStream(100)
    .take(5)       // cancels the native Flow automatically at 5 elements
    .collect { println(it) }
```

Cancellation is bidirectional: cancelling the JVM `Job` cancels the native coroutine, and vice versa.

---

## GraalVM Native Image

Nucleus Native Access includes full GraalVM metadata generation:

- `reflect-config.json` for all generated proxy classes
- `resource-config.json` for bundled native libraries
- `reachability-metadata.json` for FFM descriptors

No manual configuration needed — the generated metadata is picked up automatically by the [Nucleus GraalVM plugin](graalvm-native-image.md).

---

## Repository

Nucleus Native Access is maintained in a separate repository with its own release cycle:

[**kdroidFilter/NucleusNativeAccess**](https://github.com/kdroidFilter/NucleusNativeAccess) — plugin source, examples, full documentation, and latest releases.

The plugin ID is `io.github.kdroidfilter.nucleusnativeaccess`.
