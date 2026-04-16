# Media Control (Linux + macOS)

OS-level media controls (play/pause, next/previous, seek, metadata) for your desktop app, exposed through a single cross-platform Kotlin API:

- **Linux** — [MPRIS D-Bus specification](https://specifications.freedesktop.org/mpris-spec/latest/). Integrates with GNOME Shell, KDE Plasma, `playerctl`, sound indicators, lock screens.
- **macOS** — `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (MediaPlayer.framework). Integrates with Control Center, the Now Playing menu-bar widget, and media keys.

!!! info "Native backends"
    Linux uses GLib/GIO (`libgio-2.0`) for D-Bus. macOS uses `MediaPlayer.framework` via an Objective-C JNI bridge. No JNA, no reflection, no Java D-Bus libraries.

!!! warning "Windows not yet supported"
    On Windows, `MediaControlService.isAvailable()` returns `false` and all methods are safe no-ops. Windows SMTC support is tracked as a future enhancement.

!!! note "Platform differences"
    The events emitted by the OS differ per backend. Linux (MPRIS) can emit every `MediaControlEvent` variant. macOS (Remote Command Center) only emits `Play`, `Pause`, `Toggle`, `Next`, `Previous`, `Stop` and `SetPosition`. `SetVolume`, `OpenUri`, `Raise`, `Quit` and relative `SeekBy` are Linux-only and will never fire on macOS. `MediaControlService.setVolume(…)` is a no-op on macOS (system volume is managed separately from Now Playing).

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

Singleton entry point. All methods are safe no-ops on Windows and on platforms where the native backend fails to load.

| Property / Method | Returns | Description |
|---|---|---|
| `isAvailable()` | `Boolean` | `true` if the native backend loaded on the current OS (Linux or macOS). |
| `configure(dbusName, displayName)` | `Unit` | Configure the player identity. On Linux, registers the MPRIS bus name. On macOS, no-op. |
| `setMetadata(metadata)` | `Unit` | Update the metadata shown by the system media center. |
| `setPlaybackState(state)` | `Unit` | Update playback status and position. |
| `setVolume(volume)` | `Unit` | Update the player volume (0.0–1.0). **macOS: no-op.** |
| `attach(callback)` | `Unit` | Listen for control events from the OS. Replaces any previous listener. |
| `detach()` | `Unit` | Detach the listener and release native resources. |

#### `configure`

```kotlin
fun configure(
    dbusName: String = "org.mpris.MediaPlayer2.${NucleusApp.appId}",
    displayName: String = NucleusApp.appName ?: "Nucleus App",
)
```

- `dbusName` *(Linux only)*: full D-Bus bus name following the MPRIS spec format. Each running instance must use a unique suffix. Ignored on macOS.
- `displayName` *(Linux only)*: human-readable name shown in system media centers (MPRIS `Identity` property on the root interface). On macOS, the identity is derived from the host app bundle (`CFBundleName` / `CFBundleIdentifier`).

---

### `MediaMetadata`

| Property | Type | Description |
|---|---|---|
| `title` | `String?` | Track title. |
| `artist` | `String?` | Artist name. |
| `album` | `String?` | Album name. |
| `coverUrl` | `String?` | Cover art URL. Local files must use the `file://` scheme. Both backends also accept `http(s)://`. |
| `duration` | `Long?` | Track duration in **milliseconds**. |

Backend mapping:

- **Linux** — MPRIS `Metadata` dict: `mpris:trackid`, `mpris:length`, `mpris:artUrl`, `xesam:title`, `xesam:artist`, `xesam:album`. Time values are converted to microseconds internally as required by the spec.
- **macOS** — `MPNowPlayingInfoCenter.nowPlayingInfo` keys: `MPMediaItemPropertyTitle`, `MPMediaItemPropertyArtist`, `MPMediaItemPropertyAlbumTitle`, `MPMediaItemPropertyPlaybackDuration`, `MPMediaItemPropertyArtwork`. Artwork is loaded asynchronously on a background queue and merged once decoded.

!!! tip "Track changes"
    Each call to `setMetadata` starts a new track: on Linux it bumps `mpris:trackid`, on macOS it replaces `nowPlayingInfo` entirely (clearing any stale artwork from the previous track). Use it on track boundaries rather than on minor metadata updates.

---

### `MediaPlaybackState` / `MediaPlaybackStatus`

```kotlin
enum class MediaPlaybackStatus { STOPPED, PAUSED, PLAYING }

data class MediaPlaybackState(
    val status: MediaPlaybackStatus,
    val positionMs: Long? = null,
)
```

Call `setPlaybackState` whenever playback state changes and periodically during playback to keep the displayed position in sync.

- **Linux** — a new `positionMs` emits an MPRIS `Seeked` signal so clients (applets, lock screens) resync their progress UI.
- **macOS** — `status` maps to `MPNowPlayingPlaybackState` (Playing/Paused/Stopped) on `MPNowPlayingInfoCenter.defaultCenter`. `positionMs` is written to `MPNowPlayingInfoPropertyElapsedPlaybackTime` (seconds).

---

### `MediaControlEvent`

Sealed hierarchy of events sent by the OS to your app.

| Event | Payload | Linux (MPRIS) | macOS (RemoteCommand) |
|---|---|:---:|:---:|
| `Play` | — | ✅ | ✅ |
| `Pause` | — | ✅ | ✅ |
| `Toggle` | — | ✅ | ✅ |
| `Next` | — | ✅ | ✅ |
| `Previous` | — | ✅ | ✅ |
| `Stop` | — | ✅ | ✅ |
| `SetPosition(positionMs)` | `Long` (ms) | ✅ | ✅ |
| `SeekBy(offsetMs)` | `Long` (signed, ms) | ✅ | — |
| `SetVolume(volume)` | `Double` (0.0–1.0) | ✅ | — |
| `OpenUri(uri)` | `String` | ✅ | — |
| `Raise` | — | ✅ | — |
| `Quit` | — | ✅ | — |

macOS-only notes: the Remote Command Center does not expose a per-app volume channel, a relative seek amount, or Raise/Quit/OpenUri commands — those events simply never fire on macOS. Design your callback as an exhaustive `when` anyway: events absent on a given OS are simply ignored.

!!! info "Threading"
    The callback is dispatched on the Swing EDT — safe to mutate Compose/Swing state directly.

---

## How It Works

### Linux (MPRIS)

A dedicated thread hosts a `GMainLoop` that owns two D-Bus objects at `/org/mpris/MediaPlayer2`:

- `org.mpris.MediaPlayer2` — root interface with `Identity`, `Raise`, `Quit`.
- `org.mpris.MediaPlayer2.Player` — playback interface with `Play`, `Pause`, `Seek`, metadata, volume, etc.

State changes pushed via `setMetadata` / `setPlaybackState` / `setVolume` emit MPRIS `PropertiesChanged` signals from the main-loop thread, so clients (media applets, `playerctl`, lock screens) update in real time without polling.

### macOS (MediaPlayer.framework)

- `setMetadata` / `setPlaybackState` mutate `MPNowPlayingInfoCenter.defaultCenter` on the main queue. macOS propagates the change to Control Center and the Now Playing menu-bar widget automatically.
- `attach` installs block handlers on `MPRemoteCommandCenter.sharedCommandCenter` for `playCommand`, `pauseCommand`, `togglePlayPauseCommand`, `stopCommand`, `nextTrackCommand`, `previousTrackCommand`, and `changePlaybackPositionCommand`. Each handler emits a JSON event to Kotlin via JNI. Targets are retained so `detach` can remove them cleanly.
- Artwork URLs are decoded asynchronously on a global dispatch queue using `NSImage initWithContentsOfURL:`. A monotonic counter ensures stale artwork (from a previous track) cannot overwrite fresh metadata.

!!! info "Event loop requirement (macOS)"
    The Remote Command Center only delivers events while the main AppKit run loop is processing — which is the case by default in any Compose Desktop / Swing app. No extra setup is needed.

---

## System Integration

### Linux

The MPRIS2 specification is supported by most modern Linux desktops:

| Desktop / Tool | Metadata | Transport | Seek | Volume |
|---|---|---|---|---|
| GNOME Shell (Media player indicator) | Yes | Yes | Yes | Yes |
| KDE Plasma (Media Player applet) | Yes | Yes | Yes | Yes |
| `playerctl` CLI | Yes | Yes | Yes | Yes |
| `sound-theme-freedesktop` indicators | Yes | Yes | — | Yes |
| Lock screens (GDM, SDDM) | Yes | Yes | — | — |

Testing from the terminal:

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

### macOS

| Surface | Metadata | Transport | Seek | Artwork |
|---|---|---|---|---|
| Control Center (Now Playing widget, macOS 11+) | Yes | Yes | Yes | Yes |
| Menu-bar Now Playing icon | Yes | Yes | Yes | Yes |
| Media keys (F7/F8/F9, headset buttons, Touch Bar) | — | Yes | — | — |

Requirements:

- macOS 10.13.2 or newer (deployment target of the shipped dylib).
- The app must run with an active AppKit run loop (any Compose Desktop / Swing app does).
- No entitlements or Info.plist keys are required. `MPNowPlayingInfoCenter` works for both sandboxed and non-sandboxed apps.

---

## Native Library

Ships pre-built native binaries for Linux (x86_64 + aarch64) and macOS (arm64 + x86_64). `isAvailable` returns `false` on Windows and on platforms where the bundled binary fails to load.

| Platform | Binary | Linked against | Build requirements |
|---|---|---|---|
| Linux | `libnucleus_media_control_linux.so` | `libgio-2.0` (GLib/GIO) | `libglib2.0-dev` (Debian/Ubuntu) / `glib2-devel` (Fedora) |
| macOS | `libnucleus_media_control_macos.dylib` | `Foundation`, `AppKit`, `MediaPlayer` | Xcode Command Line Tools (clang) |

Each JVM hosts a single long-lived background worker: on Linux a dedicated thread with its own `GMainContext`, on macOS GCD blocks dispatched to the main queue.

## ProGuard

```proguard
-keep class io.github.kdroidfilter.nucleus.media.control.linux.NativeLinuxBridge {
    native <methods>;
    static ** on*(...);
}
-keep class io.github.kdroidfilter.nucleus.media.control.macos.NativeMacOsBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata is shipped automatically via `reachability-metadata.json` under `META-INF/native-image/`. No additional configuration is required.
