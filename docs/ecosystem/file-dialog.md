# File Dialog

Nucleus does not ship a file/folder picker module. The Compose Multiplatform ecosystem already has an excellent native solution.

## Recommended: FileKit

[**FileKit**](https://github.com/vinceglb/FileKit) by [@vinceglb](https://github.com/vinceglb) — native file pickers and file operations for Compose Multiplatform, including JVM Desktop.

```kotlin
dependencies {
    implementation("io.github.vinceglb:filekit-compose:<version>")
}
```

```kotlin
val launcher = rememberFilePickerLauncher(
    type = PickerType.File(extensions = listOf("png", "jpg")),
    title = "Pick an image",
) { file ->
    // file: PlatformFile?
}

Button(onClick = { launcher.launch() }) {
    Text("Pick image")
}
```

### What it covers

- Native open / save / folder pickers on macOS (`NSOpenPanel` / `NSSavePanel`), Windows (`IFileOpenDialog` / `IFileSaveDialog`), and Linux (xdg-desktop-portal `FileChooser` with fallback to GTK / zenity)
- File type filters with descriptions
- Single-file, multi-file, folder, and save modes
- Compose-friendly API with `rememberFilePickerLauncher`
- Cross-platform `PlatformFile` abstraction with read / write / metadata helpers

### Why Nucleus doesn't ship this

FileKit already covers the full surface natively, has active maintenance, and integrates cleanly with Compose state. Reimplementing it inside Nucleus would duplicate effort with no benefit.

### GraalVM native image — zero config

The Nucleus Gradle plugin ships **preloaded reachability metadata for FileKit** as part of its [library metadata bundle](../graalvm/automatic-metadata.md). The metadata is conditionally included only when `io.github.vinceglb.filekit` is present on your runtime classpath, and covers:

- macOS Foundation proxy + callback types (`FoundationLibrary`, `ID`, runnable callbacks)
- Windows JNA COM bindings (`FileDialog`, `FileOpenDialog`, `FileSaveDialog`, `ShellItem`, `Shell32`, `COMDLG_FILTERSPEC`, `PROPERTYKEY`, …)
- XDG desktop-portal D-Bus proxy (`FileChooserDbusInterface`) for Linux

So if you package with the Nucleus plugin and target `native-image`, FileKit works out of the box — no manual entries in `reachability-metadata.json`, no tracing-agent run required.
