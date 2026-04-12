# Tray API

The `Tray()` composable is the main entry point. It creates a native system tray icon with an optional context menu.

## Basic usage

```kotlin
Tray(
    icon = Icons.Default.Favorite,
    tooltip = "My App",
) {
    Item(label = "Show") { showWindow() }
    Item(label = "Quit") { exitProcess(0) }
}
```

## Icon types

`Tray()` accepts multiple icon types — use whichever fits your project:

### ImageVector

```kotlin
Tray(
    icon = Icons.Default.Notifications,
    tint = Color.White,   // optional tint
    tooltip = "My App",
) { /* menu */ }
```

### Painter

```kotlin
Tray(
    icon = painterResource("icon.png"),
    tooltip = "My App",
) { /* menu */ }
```

### DrawableResource (Compose Multiplatform)

```kotlin
Tray(
    icon = Res.drawable.app_icon,
    tooltip = "My App",
) { /* menu */ }
```

### Custom Composable

Render any `@Composable` as the tray icon — animated icons, dynamic badges, anything Compose can draw:

```kotlin
Tray(
    iconContent = {
        Box(Modifier.size(24.dp).background(Color.Red, CircleShape)) {
            Text("3", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    },
    tooltip = "3 notifications",
) { /* menu */ }
```

### Platform-specific icons

Windows uses `.ico` format natively while macOS and Linux work best with vector icons. Use platform-specific overloads to provide the optimal format for each:

```kotlin
Tray(
    windowsIcon = painterResource("icon.ico"),       // ICO for Windows
    macLinuxIcon = Icons.Default.Notifications,       // Vector for macOS/Linux
    tint = Color.White,
    tooltip = "My App",
) { /* menu */ }
```

## Icon render properties

Control how Compose icons are rasterized for the tray:

```kotlin
Tray(
    icon = Icons.Default.Favorite,
    iconRenderProperties = IconRenderProperties(
        sceneWidth = 192,
        sceneHeight = 192,
        sceneDensity = Density(2f),
        targetWidth = 44,
        targetHeight = 44,
    ),
    tooltip = "My App",
) { /* menu */ }
```

Preconfigured presets are available:

| Preset | Size | Use case |
|--------|------|----------|
| `IconRenderProperties.forCurrentOperatingSystem()` | 32x32 Win, 44x44 Mac, 24x24 Linux | Tray icon (default) |
| `IconRenderProperties.forMenuItem()` | 16x16 all platforms | Menu item icons |
| `IconRenderProperties.withoutScalingAndAliasing()` | No forced scaling | When you handle sizing yourself |

## Primary action

The `primaryAction` callback fires on left-click (Windows/macOS) or single-click (Linux, desktop-dependent):

```kotlin
Tray(
    icon = Icons.Default.Favorite,
    tooltip = "My App",
    primaryAction = { showWindow() },
) {
    // Right-click menu
    Item(label = "Quit") { exitProcess(0) }
}
```

Without a `primaryAction`, left-click opens the context menu on all platforms.

## Reactive icons

The icon updates automatically when state changes — no manual refresh:

```kotlin
var unreadCount by remember { mutableStateOf(0) }

Tray(
    icon = if (unreadCount > 0) Icons.Default.MarkEmailUnread else Icons.Default.Email,
    tooltip = "$unreadCount unread messages",
) {
    Item(label = "Mark all read") { unreadCount = 0 }
}
```
