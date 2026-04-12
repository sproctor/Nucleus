# Menu DSL

The tray menu is built with a Kotlin DSL inside the `Tray()` trailing lambda. Menus are fully reactive — change state, and items update automatically.

## Items

```kotlin
Tray(icon = Icons.Default.Favorite, tooltip = "App") {
    Item(label = "Open Settings") { openSettings() }
    Item(label = "Disabled", isEnabled = false) { }
}
```

### Items with icons

Every item type supports icons — `ImageVector`, `Painter`, `DrawableResource`, or a custom `@Composable`:

```kotlin
Item(label = "Settings", icon = Icons.Default.Settings) { openSettings() }
Item(label = "Export", icon = painterResource("export.png")) { export() }
Item(label = "Help", icon = Res.drawable.help) { openHelp() }
```

## Checkable items

Native checkmark appearance on each platform:

```kotlin
var notifications by remember { mutableStateOf(true) }
var darkMode by remember { mutableStateOf(false) }

Tray(icon = Icons.Default.Favorite, tooltip = "App") {
    CheckableItem(label = "Notifications", checked = notifications) {
        notifications = it
    }
    CheckableItem(label = "Dark Mode", icon = Icons.Default.DarkMode, checked = darkMode) {
        darkMode = it
    }
}
```

## Submenus

Nested submenus with optional icons, unlimited depth:

```kotlin
Tray(icon = Icons.Default.Favorite, tooltip = "App") {
    SubMenu(label = "Tools", icon = Icons.Default.Build) {
        Item(label = "Terminal") { openTerminal() }
        Item(label = "File Manager") { openFiles() }
        SubMenu(label = "More") {
            Item(label = "Calculator") { openCalc() }
        }
    }
}
```

## Dividers

Visual separators between groups of items:

```kotlin
Tray(icon = Icons.Default.Favorite, tooltip = "App") {
    Item(label = "Show Window") { show() }
    Divider()
    Item(label = "Settings") { settings() }
    Item(label = "About") { about() }
    Divider()
    Item(label = "Quit") { exitProcess(0) }
}
```

## Reactive menus

The entire menu tree participates in Compose recomposition. Conditionally show items, update labels, toggle states — the menu rebuilds natively:

```kotlin
var isConnected by remember { mutableStateOf(false) }
var showAdvanced by remember { mutableStateOf(false) }

Tray(icon = Icons.Default.Wifi, tooltip = "Network") {
    Item(label = if (isConnected) "Disconnect" else "Connect") {
        isConnected = !isConnected
    }

    CheckableItem(label = "Advanced Options", checked = showAdvanced) {
        showAdvanced = it
    }

    if (showAdvanced) {
        Divider()
        SubMenu(label = "Advanced") {
            Item(label = "DNS Settings") { }
            Item(label = "Proxy") { }
        }
    }

    Divider()
    Item(label = "Quit") { exitProcess(0) }
}
```
