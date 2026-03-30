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

---

## Example: Take a Screenshot on macOS

Here's a real-world example: capturing the screen using macOS's CoreGraphics API. This is a platform API with no JVM equivalent — the kind of thing that would normally require JNI C glue.

**Native side** (`src/nativeMain/kotlin/com/example/screen/ScreenCapture.kt`):

```kotlin
package com.example.screen

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*

class ScreenCapture {

    fun capture(): ByteArray {
        val image = CGWindowListCreateImage(
            CGRectInfinite,
            kCGWindowListOptionOnScreenOnly,
            kCGNullWindowID,
            kCGWindowImageDefault
        ) ?: return ByteArray(0)

        val provider = CGImageGetDataProvider(image)
        val data = CGDataProviderCopyData(provider)

        val bytes = CFDataGetBytePtr(data)
        val length = CFDataGetLength(data).toInt()
        val result = ByteArray(length) { bytes!![it] }

        CFRelease(data)
        CGImageRelease(image)
        return result
    }

    fun width(): Int {
        val image = CGWindowListCreateImage(
            CGRectInfinite,
            kCGWindowListOptionOnScreenOnly,
            kCGNullWindowID,
            kCGWindowImageDefault
        ) ?: return 0
        val w = CGImageGetWidth(image).toInt()
        CGImageRelease(image)
        return w
    }

    fun height(): Int {
        val image = CGWindowListCreateImage(
            CGRectInfinite,
            kCGWindowListOptionOnScreenOnly,
            kCGNullWindowID,
            kCGWindowImageDefault
        ) ?: return 0
        val h = CGImageGetHeight(image).toInt()
        CGImageRelease(image)
        return h
    }
}
```

**JVM side** — the plugin generates the proxy, you just use it:

```kotlin
import com.example.screen.ScreenCapture

fun main() {
    val capture = ScreenCapture()

    val pixels = capture.capture()          // ByteArray, straight from CoreGraphics
    val w = capture.width()
    val h = capture.height()

    println("Captured ${w}×${h} pixels (${pixels.size} bytes)")
    capture.close()                         // explicit release (or auto-GC'd)
}
```

**No C. No JNI headers. No build scripts. No `System.loadLibrary` call.** The `.dylib` is compiled by the plugin, bundled in the JAR, and extracted automatically at runtime.

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

You don't have to wrap everything in a class. Top-level functions are grouped into a singleton object on the JVM side:

```kotlin
// nativeMain — top-level function
package com.example.utils

fun currentProcessId(): Int = platform.posix.getpid()
```

```kotlin
// jvmMain — generated singleton
import com.example.utils.UtilsFunctions

val pid = UtilsFunctions.currentProcessId()
```

---

## Supported Types

The plugin handles marshalling automatically for all common types:

| Type | Notes |
|------|-------|
| `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short` | Direct pass-through |
| `String` | UTF-8, output-buffer pattern |
| `ByteArray` | Pointer + size |
| `data class` | Fields decomposed into flat C ABI arguments |
| `enum class` | Ordinal mapping |
| `List<T>`, `Set<T>`, `Map<K, V>` | Pointer + count arrays |
| `T?` (nullable) | Sentinel-based encoding |
| `(T) -> R` (lambda / callback) | FFM upcall stub |
| `suspend fun` | Transparent coroutine mapping with cancellation |
| `Flow<T>` | `channelFlow` on JVM, auto-cancels |
| `Object` (class instances) | Opaque `StableRef` handle, lifecycle tracked |

### What stays in commonMain

Some constructs don't cross the FFM boundary and should stay in shared code:

- Interfaces, abstract classes, sealed classes
- Inheritance hierarchies
- Generic class definitions
- Nested collections (`List<List<T>>`)

---

## Object Lifecycle

Generated proxy classes implement `AutoCloseable`. Native memory is freed when you call `close()`, or automatically when the object is garbage collected (via Java `Cleaner`):

```kotlin
// Explicit — preferred in performance-sensitive code
val capture = ScreenCapture()
try {
    val pixels = capture.capture()
    // ...
} finally {
    capture.close()
}

// Or use-style
ScreenCapture().use { capture ->
    val pixels = capture.capture()
}

// Implicit — GC will clean up, but timing is non-deterministic
val capture = ScreenCapture()
val pixels = capture.capture()
// no close() — Cleaner will release when GC runs
```

---

## Coroutines and Flows

Suspend functions and `Flow` are fully transparent:

```kotlin
// nativeMain
suspend fun fetchFromNative(query: String): String {
    delay(50)
    return "result: $query"
}

fun nativeEventStream(): Flow<Int> = flow {
    repeat(10) { emit(it); delay(100) }
}
```

```kotlin
// jvmMain — no special wiring needed
val result = NativeFunctions.fetchFromNative("hello")

NativeFunctions.nativeEventStream()
    .take(5)          // automatically cancels native coroutine
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

## Performance Notes

| Scenario | Recommendation |
|----------|---------------|
| Compute-bound work (stays native) | **Excellent** — native finishes faster, no JIT warmup needed |
| Many small calls across the boundary | Add a batching layer — FFM crossing costs ~50 ns/call |
| Memory-intensive work | **Excellent** — native allocations don't pressure the JVM heap |
| String-heavy APIs | Fine for reasonable volumes — UTF-8 copy happens at boundary |

FFM is not slower than JNI for individual calls, but the overhead is measurable at very high call rates. The right pattern is to move work into native and minimize how often you cross the boundary — not to use native as a per-operation cache.

---

## Repository

Nucleus Native Access is maintained in a separate repository with its own release cycle:

[**kdroidFilter/NucleusNativeAccess**](https://github.com/kdroidFilter/NucleusNativeAccess) — plugin source, examples, full documentation, and latest releases.

The plugin ID is `io.github.kdroidfilter.nucleusnativeaccess`.
