# Utilities

ComposeNativeTray includes several utilities that are essential for tray-based applications.

## Single Instance Manager

Most tray apps should only run one instance. `SingleInstanceManager` enforces this and lets the second launch restore the existing window:

```kotlin
fun main() {
    val isSingleInstance = SingleInstanceManager.isSingleInstance(
        onRestoreRequest = {
            // Called when another instance tries to start
            isWindowVisible = true
        }
    )

    if (!isSingleInstance) {
        // Another instance is already running — it received the restore request
        return
    }

    application {
        // Your app...
    }
}
```

### Custom configuration

```kotlin
SingleInstanceManager.configuration = SingleInstanceManager.Configuration(
    lockDirectory = Path("/custom/lock/dir"),
    appIdentifier = "com.example.myapp",
)
```

### Data passing between instances

The new instance can write data to a temporary file that the existing instance reads:

```kotlin
val isSingleInstance = SingleInstanceManager.isSingleInstance(
    onRestoreFileCreated = { path ->
        // New instance writes data here
        path.writeText("open-file:/path/to/file.txt")
    },
    onRestoreRequest = { path ->
        // Existing instance reads it
        val data = path.readText()
        handleDeepLink(data)
    }
)
```

## Tray Position Detection

Position a window next to the tray icon — essential for `TrayApp`-style popup windows:

```kotlin
// Get the optimal window position anchored to the tray icon
val position = getTrayWindowPosition(width = 300, height = 400)

// Or detect which corner/edge the tray is on
val trayPosition: TrayPosition? = getTrayPosition()
// Returns: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, or BOTTOM_RIGHT
```

Works on all platforms — uses native APIs to detect the tray icon click position and compute the best window placement.

## Dark Mode Detection

Detect whether the menu bar / system tray area is in dark mode — useful for tinting icons:

```kotlin
@Composable
fun MyTray() {
    val isDark = isMenuBarInDarkMode()

    Tray(
        icon = Icons.Default.Notifications,
        tint = if (isDark) Color.White else Color.Black,
        tooltip = "My App",
    ) { /* menu */ }
}
```

Platform behavior:

| Platform | Detection method |
|----------|-----------------|
| macOS | Menu bar color (adapts to wallpaper on macOS Ventura+) |
| Windows | System theme setting |
| Linux (GNOME/XFCE/Cinnamon/MATE) | Always reports dark (panel is dark) |
| Linux (KDE) | System theme setting |

The value updates reactively — if the user changes their theme, the tray icon adapts instantly.
