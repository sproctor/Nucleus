# Media Control (Linux)

OS-level media controls (play/pause, next/previous, seek, metadata, volume) via the [MPRIS D-Bus specification](https://specifications.freedesktop.org/mpris-spec/latest/) on Linux. Expose your media player to the system media center — GNOME Shell, KDE Plasma, `playerctl`, `sound indicators`, lock screens — through a single JNI bridge.

!!! info "Pure D-Bus via GIO"
    Uses GLib/GIO (`libgio-2.0`) for D-Bus communication — no JNA, no reflection, no Java D-Bus libraries.

!!! warning "Linux only"
    MPRIS is a freedesktop specification specific to Linux. On Windows and macOS, `MediaControlService.isAvailable()` returns `false` and all methods are safe no-ops. For cross-platform media controls, use platform-specific APIs directly (SMTC on Windows, `MPNowPlayingInfoCenter` on macOS).

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.media-control:<version>")
}
```

Depends on `core-runtime` (for `NativeLibraryLoader` and `NucleusApp`) and `kotlinx-serialization-json` (event deserialization).

## Quick Start

```kotlin
import io.github.kdroidfilter.nucleus.media.control.*

if (MediaControlService.isAvailable()) {
    // 1. Configure once — typically at app startup
    MediaControlService.configure()

    // 2. Listen to OS events (play/pause from headphones, media keys, lock screen…)
    MediaControlService.attach { event ->
        when (event) {
            MediaControlEvent.Play     -> player.play()
            MediaControlEvent.Pause    -> player.pause()
            MediaControlEvent.Toggle   -> player.togglePlayPause()
            MediaControlEvent.Next     -> player.skipToNext()
            MediaControlEvent.Previous -> player.skipToPrevious()
            MediaControlEvent.Stop     -> player.stop()
            is MediaControlEvent.SeekBy      -> player.seekBy(event.offsetMs)
            is MediaControlEvent.SetPosition -> player.seekTo(event.positionMs)
            is MediaControlEvent.SetVolume   -> player.setVolume(event.volume)
            is MediaControlEvent.OpenUri     -> player.open(event.uri)
            MediaControlEvent.Raise    -> window.toFront()
            MediaControlEvent.Quit     -> app.exit()
        }
    }

    // 3. Push state to the system media center
    MediaControlService.setMetadata(MediaMetadata(
        title = "Nocturne Op. 9 No. 2",
        artist = "Frédéric Chopin",
        album = "Nocturnes",
        duration = 270_000,
    ))
    MediaControlService.setPlaybackState(MediaPlaybackState(
        status = MediaPlaybackStatus.PLAYING,
        positionMs = 0,
    ))
}

// On shutdown
MediaControlService.detach()
```

---

## API Reference

### `MediaControlService`

Singleton entry point. All methods are safe no-ops on Windows/macOS.

| Property / Method | Returns | Description |
|---|---|---|
| `isAvailable()` | `Boolean` | `true` if the native library loaded on Linux. |
| `configure(dbusName, displayName)` | `Unit` | Configure the MPRIS player identity. Call once before `attach`. |
| `setMetadata(metadata)` | `Unit` | Update the metadata shown by the system media center. |
| `setPlaybackState(state)` | `Unit` | Update playback status and position. |
| `setVolume(volume)` | `Unit` | Update the player volume (0.0–1.0). |
| `attach(callback)` | `Unit` | Listen for control events from the OS. Replaces any previous listener. |
| `detach()` | `Unit` | Detach the listener and release D-Bus resources. |

#### `configure`

```kotlin
fun configure(
    dbusName: String = "org.mpris.MediaPlayer2.${NucleusApp.appId}",
    displayName: String = NucleusApp.appName ?: "Nucleus App",
)
```

- `dbusName`: full D-Bus bus name following the MPRIS spec format. Each running instance must use a unique suffix.
- `displayName`: human-readable name shown in system media centers (`Identity` property on the root interface).

---

### `MediaMetadata`

| Property | Type | Description |
|---|---|---|
| `title` | `String?` | Track title. |
| `artist` | `String?` | Artist name. |
| `album` | `String?` | Album name. |
| `coverUrl` | `String?` | Cover art URL. For local files, use the `file://` scheme. |
| `duration` | `Long?` | Track duration in **milliseconds**. |

Mapped to MPRIS `Metadata` dict — `mpris:trackid`, `mpris:length`, `mpris:artUrl`, `xesam:title`, `xesam:artist`, `xesam:album`. Time values are converted to microseconds internally as required by the spec.

!!! tip "Track changes"
    Each call to `setMetadata` increments an internal `mpris:trackid`, signalling to listeners that a new track started. Use this on track boundaries rather than on minor metadata updates.

---

### `MediaPlaybackState` / `MediaPlaybackStatus`

```kotlin
enum class MediaPlaybackStatus { STOPPED, PAUSED, PLAYING }

data class MediaPlaybackState(
    val status: MediaPlaybackStatus,
    val positionMs: Long? = null,
)
```

Call `setPlaybackState` whenever playback state changes and periodically during playback to keep the displayed position in sync. Setting a new `positionMs` emits an MPRIS `Seeked` signal, which clients use to resync their progress UI.

---

### `MediaControlEvent`

Sealed hierarchy of events sent by the OS to your app.

| Event | Payload | Triggered by |
|---|---|---|
| `Play` | — | Play button, media key, headset "play". |
| `Pause` | — | Pause button, media key. |
| `Toggle` | — | MPRIS `PlayPause`, most headset play-pause buttons. |
| `Next` | — | Next button, media key. |
| `Previous` | — | Previous button, media key. |
| `Stop` | — | Stop button. |
| `SeekBy(offsetMs)` | `Long` (signed, ms) | Scrubbing forward/backward. Negative = backward. |
| `SetPosition(positionMs)` | `Long` (ms) | User dragged the progress bar. |
| `SetVolume(volume)` | `Double` (0.0–1.0) | Volume slider in the media center. |
| `OpenUri(uri)` | `String` | External app requests to open a URI. |
| `Raise` | — | Request to bring the player window to the front. |
| `Quit` | — | Request to quit the player. |

!!! info "Threading"
    The callback is dispatched on the Swing EDT — safe to mutate Compose/Swing state directly.

---

## How It Works

`media-control` runs a dedicated thread hosting a `GMainLoop` that owns two D-Bus objects at `/org/mpris/MediaPlayer2`:

- `org.mpris.MediaPlayer2` — root interface with `Identity`, `Raise`, `Quit`.
- `org.mpris.MediaPlayer2.Player` — playback interface with `Play`, `Pause`, `Seek`, metadata, volume, etc.

State changes pushed via `setMetadata` / `setPlaybackState` / `setVolume` emit MPRIS `PropertiesChanged` signals from the main-loop thread, so clients (media applets, `playerctl`, lock screens) update in real time without polling.

---

## Desktop Environment Support

The MPRIS2 specification is supported by most modern Linux desktops:

| Desktop / Tool | Metadata | Transport | Seek | Volume |
|---|---|---|---|---|
| GNOME Shell (Media player indicator) | Yes | Yes | Yes | Yes |
| KDE Plasma (Media Player applet) | Yes | Yes | Yes | Yes |
| `playerctl` CLI | Yes | Yes | Yes | Yes |
| `sound-theme-freedesktop` indicators | Yes | Yes | — | Yes |
| Lock screens (GDM, SDDM) | Yes | Yes | — | — |

### Testing from the Terminal

```bash
# Show current metadata
playerctl --player=<dbusName> metadata

# Control playback
playerctl --player=<dbusName> play-pause
playerctl --player=<dbusName> next
playerctl --player=<dbusName> position 60

# Watch PropertiesChanged signals in real time
dbus-monitor --session "type='signal',interface='org.freedesktop.DBus.Properties'"
```

---

## Native Library

Ships pre-built Linux shared libraries (x86_64 + aarch64). `isAvailable` returns `false` on other platforms.

- `libnucleus_media_control_linux.so` — linked against `libgio-2.0` (GLib/GIO)
- Build requirement: `libglib2.0-dev` (Debian/Ubuntu) or `glib2-devel` (Fedora)
- One D-Bus service thread per JVM with its own `GMainContext`

## ProGuard

```proguard
-keep class io.github.kdroidfilter.nucleus.media.control.linux.NativeLinuxBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata is shipped automatically via `reachability-metadata.json` under `META-INF/native-image/`. No additional configuration is required.
