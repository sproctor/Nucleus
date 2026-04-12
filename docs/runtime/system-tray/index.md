# System Tray

!!! note "Separate repository"
    System Tray is maintained in its own repository with its own release cycle: [**kdroidFilter/ComposeNativeTray**](https://github.com/kdroidFilter/ComposeNativeTray). The artifact is `io.github.kdroidfilter:composenativetray`.

**ComposeNativeTray** is a **complete system tray framework** for Compose Desktop — the simplest and most powerful way to create system tray applications today, on any platform.

Native menus, reactive state, HiDPI icons, dark mode awareness, popup windows anchored to the tray, and a pure Compose DSL — on macOS, Windows, and Linux.

## Features

- **Native platform menus** — Not a custom-drawn overlay. Real OS menus, with the look and behavior users expect.
- **Any icon type** — `ImageVector`, `Painter`, `DrawableResource`, or any `@Composable`. Crisp on every display.
- **Fully reactive** — Change state and the icon, labels, checkmarks, and menu structure all update instantly.
- **Checkable items** — Native checkmark appearance on each platform.
- **Nested submenus** — With optional icons, unlimited depth.
- **Primary action** — Left-click callback with per-platform behavior.
- **Dark mode awareness** — Automatic icon tinting based on the system theme.
- **Tray position detection** — Position windows relative to the tray icon.
- **TrayApp** — A full Compose popup window anchored to the tray icon, like macOS menu bar apps.

## Quick example

```kotlin
var isDarkMode by remember { mutableStateOf(false) }

Tray(
    icon = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
    tooltip = "My App",
    primaryAction = { showWindow() },
) {
    Item(label = "Show Window") { showWindow() }
    Divider()
    CheckableItem(label = "Dark Mode", checked = isDarkMode) {
        isDarkMode = !isDarkMode
    }
    SubMenu(label = "Options") {
        Item(label = "Settings") { openSettings() }
        Item(label = "About") { openAbout() }
    }
    Divider()
    Item(label = "Quit") { exitProcess(0) }
}
```

The menu is fully reactive — change `isDarkMode` and the icon, label, and checkmark all update instantly. No manual menu rebuild.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composenativetray:<version>")
}
```

## Next steps

- [Tray API](tray-api.md) — `Tray()` composable, icon types, primary actions, platform-specific icons
- [Menu DSL](menu-dsl.md) — Items, checkable items, submenus, dividers, icons in menus
- [TrayApp](tray-app.md) — Popup window anchored to the tray icon
- [Utilities](utilities.md) — Tray position detection, dark mode detection
