# Badge (Windows)

Windows Badge Notifications API via JNI — display numeric counts or status glyph icons on the app's taskbar button and Start tile.

## Dependency

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.badge-windows:<version>")
}
```

## Overview

Badges are small indicators that appear on the app's taskbar button and Start tile. They can display:

- **Numeric values** (1–99, shown as "99+" for values >= 100)
- **Status glyphs** (predefined icons like alert, busy, playing, etc.)

The badge API requires a one-time initialization with an AUMID (Application User Model ID), which is resolved automatically by Nucleus.

## Quick Start

```kotlin
import io.github.kdroidfilter.nucleus.badge.windows.BadgeGlyph
import io.github.kdroidfilter.nucleus.badge.windows.WindowsBadgeManager

// Initialize once at app startup
if (WindowsBadgeManager.isAvailable) {
    WindowsBadgeManager.initialize()
}

// Set a numeric badge (e.g., unread message count)
WindowsBadgeManager.setCount(5)

// Set a glyph badge
WindowsBadgeManager.setGlyph(BadgeGlyph.NEW_MESSAGE)

// Clear the badge
WindowsBadgeManager.clear()

// Clean up on shutdown
WindowsBadgeManager.uninitialize()
```

## API Reference

### `WindowsBadgeManager`

Thread-safe singleton providing the full badge API.

#### Initialization

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded on this platform |
| `initialize(aumid: String? = null): Boolean` | Initialize the badge subsystem. AUMID is auto-resolved from package identity (APPX) or `NucleusApp.appId` (unpackaged) |
| `uninitialize()` | Release native resources |

#### Badge Operations

| Method | Description |
|--------|-------------|
| `setCount(count: Int): Boolean` | Set a numeric badge (0 clears, 1–99 shown as number, 100+ as "99+") |
| `setGlyph(glyph: BadgeGlyph): Boolean` | Set a glyph badge icon |
| `clear(): Boolean` | Remove the badge entirely |

### `BadgeGlyph`

Predefined status glyph icons.

| Glyph | XML Value | Description |
|-------|-----------|-------------|
| `NONE` | `none` | Clears the badge |
| `ACTIVITY` | `activity` | Activity indicator |
| `ALARM` | `alarm` | Alarm status |
| `ALERT` | `alert` | Alert / requires attention |
| `ATTENTION` | `attention` | Attention required |
| `AVAILABLE` | `available` | Presence: available |
| `AWAY` | `away` | Presence: away |
| `BUSY` | `busy` | Presence: busy |
| `ERROR` | `error` | Error / failure |
| `NEW_MESSAGE` | `newMessage` | New message received |
| `PAUSED` | `paused` | Paused state |
| `PLAYING` | `playing` | Playing / active |
| `UNAVAILABLE` | `unavailable` | Presence: unavailable |

## AUMID Resolution

The AUMID (Application User Model ID) identifies your app to Windows for badge delivery:

| Packaging | AUMID Source |
|-----------|-------------|
| **APPX/MSIX** | Automatic — Windows uses the package identity |
| **Unpackaged (EXE, MSI, dev)** | Auto-derived from `NucleusApp.appId` |
| **Explicit override** | Pass to `initialize(aumid = "com.example.MyApp")` |

For unpackaged apps, the native code also creates a Start Menu shortcut with the AUMID property so that Windows can associate the badge with the app.

## Compose Integration

```kotlin
@Composable
fun BadgeDemo() {
    var badgeCount by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        if (WindowsBadgeManager.isAvailable) {
            WindowsBadgeManager.initialize()
        }
        onDispose {
            WindowsBadgeManager.uninitialize()
        }
    }

    Column {
        Button(onClick = {
            badgeCount++
            WindowsBadgeManager.setCount(badgeCount)
        }) {
            Text("Increment Badge ($badgeCount)")
        }

        Button(onClick = {
            WindowsBadgeManager.setGlyph(BadgeGlyph.ALERT)
        }) {
            Text("Alert Glyph")
        }

        Button(onClick = {
            badgeCount = 0
            WindowsBadgeManager.clear()
        }) {
            Text("Clear Badge")
        }
    }
}
```

## Shared Initialization with Toast Notifications

If your app also uses `notification-windows` for toast notifications, both modules share the same AUMID concept. You can initialize them independently — each module manages its own WinRT updater.

```kotlin
// Both can be initialized independently
WindowsNotificationCenter.initialize()
WindowsBadgeManager.initialize()
```

## Platform Notes

- Badges are a Windows-only feature. `isAvailable` returns `false` on other platforms.
- Numeric badges display 1–99 directly. Values >= 100 display as "99+". A value of 0 clears the badge.
- Badge updates are synchronous — no callback is needed.
- The Start Menu shortcut created for unpackaged apps is idempotent (checked before creation).

## ProGuard

When ProGuard is enabled, the Nucleus Gradle plugin automatically includes the required keep rules. No manual configuration is needed.

## GraalVM Native Image

The module ships with `reachability-metadata.json` for GraalVM native-image compatibility. No additional configuration is required.
