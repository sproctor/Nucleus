# Launcher (Windows)

Windows Launcher API via JNI — badge notifications and jump lists for the app's taskbar button.

## Dependency

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.launcher-windows:<version>")
}
```

## Overview

This module provides two Windows launcher APIs:

- **Badge Notifications** — numeric counts (1–99+) or status glyph icons on the taskbar button and Start tile (APPX/MSIX only)
- **Jump Lists** — custom categories and pinned tasks in the taskbar right-click menu (all packaging types)

---

## Badge Notifications

### Quick Start

```kotlin
import io.github.kdroidfilter.nucleus.launcher.windows.BadgeGlyph
import io.github.kdroidfilter.nucleus.launcher.windows.WindowsBadgeManager

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

### `WindowsBadgeManager`

Thread-safe singleton providing the full badge API. Requires APPX/MSIX packaging.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded on this platform |
| `initialize(aumid: String? = null): Boolean` | Initialize the badge subsystem |
| `uninitialize()` | Release native resources |
| `setCount(count: Int): Boolean` | Set a numeric badge (0 clears, 1–99 shown as number, 100+ as "99+") |
| `setGlyph(glyph: BadgeGlyph): Boolean` | Set a glyph badge icon |
| `clear(): Boolean` | Remove the badge entirely |

### `BadgeGlyph`

Predefined status glyph icons:

| Glyph | Value | Description |
|-------|-------|-------------|
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

---

## Jump Lists

Jump lists appear when the user right-clicks the app's taskbar button. They support custom categories, shell-managed categories (Recent/Frequent), and pinned user tasks.

### Quick Start

```kotlin
import io.github.kdroidfilter.nucleus.launcher.windows.JumpListCategory
import io.github.kdroidfilter.nucleus.launcher.windows.JumpListItem
import io.github.kdroidfilter.nucleus.launcher.windows.WindowsJumpListManager

// Set a jump list with categories and tasks
WindowsJumpListManager.setJumpList(
    categories = listOf(
        JumpListCategory(
            name = "Recent Projects",
            items = listOf(
                JumpListItem(title = "Project A", arguments = "--open=project-a"),
                JumpListItem(title = "Project B", arguments = "--open=project-b"),
            ),
        ),
    ),
    tasks = listOf(
        JumpListItem(title = "New Window", arguments = "--new-window"),
        JumpListItem.SEPARATOR,
        JumpListItem(title = "Settings", arguments = "--settings"),
    ),
)

// Clear the jump list
WindowsJumpListManager.clearJumpList()
```

### `WindowsJumpListManager`

Thread-safe singleton. Works for both APPX/MSIX and unpackaged apps.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded |
| `setJumpList(categories, tasks, knownCategories): Boolean` | Set the entire jump list atomically |
| `clearJumpList(): Boolean` | Remove all jump list entries |

### `JumpListItem`

A single clickable item in a jump list.

| Property | Type | Description |
|----------|------|-------------|
| `title` | `String` | Display text |
| `arguments` | `String` | Command-line args passed when clicked |
| `description` | `String` | Tooltip text |
| `iconPath` | `String` | Icon file path (empty = app icon) |
| `iconIndex` | `Int` | Icon resource index |
| `isSeparator` | `Boolean` | Whether this is a separator (tasks only) |

Use `JumpListItem.SEPARATOR` for visual separators in the tasks section.

### `JumpListCategory`

A named group of items.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Category display name (must be unique) |
| `items` | `List<JumpListItem>` | Items in this category |

### `KnownCategory`

Shell-managed categories populated automatically by Windows.

| Value | Description |
|-------|-------------|
| `FREQUENT` | Frequently used destinations |
| `RECENT` | Recently used destinations |

### How Click Handling Works

Unlike macOS (which uses in-process `NSMenu` delegates) or Linux (which uses D-Bus callbacks),
Windows jump lists have **no in-process callback mechanism**. When a user clicks a jump list item,
Windows **launches a new process** of your application with the item's `arguments` appended to the
command line.

To handle this in a running application, you need two pieces from `core-runtime`:

1. **`SingleInstanceManager`** — detects that a second instance was launched and forwards data to the primary instance via a file-based IPC mechanism.
2. **`DeepLinkHandler`** — parses URI-style arguments from the command line and provides a callback when a deep link is received.

#### Setup in `main()`

```kotlin
fun main(args: Array<String>) {
    // 1. Set the AUMID before any window is created (unpackaged apps only)
    WindowsJumpListManager.setProcessAppId()

    // 2. Register the deep link handler — parses any URI from CLI args
    DeepLinkHandler.register(args) { uri ->
        println("Received deep link: $uri")
        // Handle the URI (navigate, open file, etc.)
    }

    application {
        // 3. Enforce single instance with argument forwarding
        val isFirstInstance = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    // Secondary instance: write the received URI to the IPC file
                    DeepLinkHandler.writeUriTo(this)
                },
                onRestoreRequest = {
                    // Primary instance: read the URI from the IPC file
                    DeepLinkHandler.readUriFrom(this)
                    // Bring window to front
                    isWindowVisible = true
                },
            )
        }

        // 4. If this is a secondary instance, exit immediately
        if (!isFirstInstance) {
            exitApplication()
            return@application
        }

        // ... create your window
    }
}
```

#### Complete Flow

```
User clicks jump list item ("Open Dashboard", arguments = "myapp://dashboard")
    │
    ▼
Windows launches: myapp.exe myapp://dashboard
    │
    ▼
main(args = ["myapp://dashboard"]) starts
    │
    ├─► DeepLinkHandler.register(args) parses "myapp://dashboard" as a URI
    │
    ▼
SingleInstanceManager.isSingleInstance() tries to acquire lock
    │
    ├─ Lock FAILS (primary instance holds it)
    │   ├─► onRestoreFileCreated: writes URI to .restore_request file
    │   └─► Secondary instance exits
    │
    ▼
Primary instance's WatchService detects .restore_request file
    │
    ├─► onRestoreRequest: DeepLinkHandler.readUriFrom(path)
    │   └─► onDeepLink callback fires with URI("myapp://dashboard")
    │
    └─► Window brought to front, URI handled
```

#### Jump List Items with URI Arguments

Use URI-style arguments so `DeepLinkHandler` can parse them automatically:

```kotlin
WindowsJumpListManager.setJumpList(
    categories = listOf(
        JumpListCategory(
            name = "Recent Projects",
            items = listOf(
                JumpListItem(
                    title = "Open Dashboard",
                    arguments = "myapp://dashboard",
                ),
                JumpListItem(
                    title = "Open Settings",
                    arguments = "myapp://settings",
                ),
            ),
        ),
    ),
)
```

> **Note:** Jump list items require a real application executable. They do **not** work
> in Gradle `run` dev mode (where the process is `java.exe`). Test with `runDistributable`
> or a packaged build (APPX, NSIS, MSI).

---

## AUMID Resolution

The AUMID (Application User Model ID) identifies your app to Windows:

| Packaging | Badge | Jump List |
|-----------|-------|-----------|
| **APPX/MSIX** | Automatic (package identity) | Automatic (package identity) |
| **Unpackaged** | Not supported | Auto-derived from `NucleusApp.appId` |

## Platform Notes

- Badge notifications require APPX/MSIX packaging (WinRT limitation).
- Jump lists work for all packaging types.
- All operations are synchronous.
- `isAvailable` returns `false` on non-Windows platforms.

## ProGuard

When ProGuard is enabled, the Nucleus Gradle plugin automatically includes the required keep rules. No manual configuration is needed.

## GraalVM Native Image

The module ships with `reachability-metadata.json` for GraalVM native-image compatibility. No additional configuration is required.
