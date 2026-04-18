# Auto-Launch

Cross-platform auto-launch at user login for JVM desktop applications.

Nucleus auto-detects the runtime packaging at startup (via `ExecutableRuntime`) and dispatches to the correct backend:

| Packaging | API used | Detection signal |
|---|---|---|
| MSIX | `Windows.ApplicationModel.StartupTask` (WinRT) | `GetCurrentPackageFullName` succeeds |
| Win32 (MSI / NSIS) | `HKCU\...\Run` + `HKCU\...\Explorer\StartupApproved\Run` | Process is not packaged |
| macOS DMG / PKG | `SMAppService.mainApp` (macOS 13+) | Always routed to SMAppService — works for DMG (Developer ID) and PKG (Mac App Store, sandboxed) |
| macOS < 13 | — (returns `UNSUPPORTED`) | — |
| Linux (deb / rpm / AppImage / dev) | systemd user service via `org.freedesktop.systemd1` | `ExecutableRuntime.isFlatpak() == false` |
| Linux (Flatpak) | `org.freedesktop.portal.Background.RequestBackground` | `ExecutableRuntime.isFlatpak() == true` |

The runtime exposes a single unified API — consumers don't need to branch on packaging themselves.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.autolaunch:<version>")
}
```

```kotlin
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunch
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunchResult
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunchState
```

## Usage

```kotlin
when (val state = AutoLaunch.state()) {
    AutoLaunchState.ENABLED           -> println("Starts at login")
    AutoLaunchState.DISABLED          -> AutoLaunch.enable()
    AutoLaunchState.DISABLED_BY_USER  -> AutoLaunch.openSystemSettings()
    AutoLaunchState.DISABLED_BY_POLICY,
    AutoLaunchState.ENABLED_BY_POLICY -> { /* read-only, GPO */ }
    AutoLaunchState.UNSUPPORTED       -> { /* macOS < 13, unsupported Linux env */ }
}
```

Toggling from a Compose UI:

```kotlin
Switch(
    checked = AutoLaunch.isEnabled(),
    enabled = !AutoLaunch.isUserLocked(),
    onCheckedChange = { checked ->
        val result = if (checked) AutoLaunch.enable() else AutoLaunch.disable()
        // Show a message if result == BLOCKED_BY_USER
    },
)
```

## API

### `AutoLaunch`

| Method | Returns | Notes |
|---|---|---|
| `state()` | `AutoLaunchState` | Current auto-launch state |
| `isEnabled()` | `Boolean` | `true` for `ENABLED` or `ENABLED_BY_POLICY` |
| `isUserLocked()` | `Boolean` | `true` when state is `DISABLED_BY_USER` |
| `enable()` | `AutoLaunchResult` | See rules below |
| `disable()` | `AutoLaunchResult` | — |
| `openSystemSettings()` | `Boolean` | Opens `ms-settings:startupapps` on Windows; `com.apple.LoginItems-Settings.extension` on macOS; no-op on Linux (no cross-DE "startup apps" URL) |
| `wasStartedAtLogin(args)` | `Boolean` | `true` if the process was launched by auto-launch. Works on both Win32 and MSIX |

### `AutoLaunchState`

| Value | Meaning |
|---|---|
| `ENABLED` | Will start at next logon |
| `DISABLED` | Not configured |
| `DISABLED_BY_USER` | **User toggled off via Task Manager / Settings — programmatic re-enable is blocked** |
| `DISABLED_BY_POLICY` | Blocked by Group Policy (MSIX only) |
| `ENABLED_BY_POLICY` | Forced on by Group Policy (MSIX only) |
| `UNSUPPORTED` | Platform or packaging not supported (macOS < 13, Linux without systemd / portal, missing JNI lib) |

### `AutoLaunchResult`

| Value | Meaning |
|---|---|
| `OK` | State changed |
| `UNCHANGED` | State already matches the request |
| `BLOCKED_BY_USER` | User has explicitly disabled via system UI — do NOT retry |
| `BLOCKED_BY_POLICY` | Group Policy blocks the change |
| `UNSUPPORTED` | Platform not supported |
| `ERROR` | Native call failed |

## MSIX auto-injection

For MSIX packages, the Windows runtime requires a `<uap5:StartupTask TaskId="...">` entry in the application manifest. The Nucleus Gradle plugin injects this automatically when you enable the extension in your `appx { }` block:

```kotlin
nucleus.application {
    nativeDistributions {
        windows {
            appx {
                applicationId = "NucleusDemo"
                publisherDisplayName = "KDroidFilter"
                displayName = "Nucleus Demo"

                // 1. Inject <desktop:Extension Category="windows.startupTask"> into Package.appxmanifest
                addAutoLaunchExtension = true

                // 2. Override the TaskId (rarely needed). electron-builder hardcodes
                //    "SlackStartup" in the generated manifest; the plugin exposes that
                //    same value at runtime via NucleusApp.startupTaskId.
                //    Only override if you also post-process AppxManifest.xml yourself.
                // startupTaskId = "MyCustomStartupId"
            }
        }
    }
}
```

With `addAutoLaunchExtension = true`, the plugin:

1. Instructs electron-builder to inject the `windows.startupTask` extension into the generated `Package.appxmanifest`.
2. Writes the TaskId used in that manifest (`"SlackStartup"` — hardcoded by electron-builder for legacy reasons) into the app metadata resource (`nucleus-app.properties`) so it's available at runtime as `NucleusApp.startupTaskId`.
3. The `autolaunch` module picks that up automatically — no code changes required.

!!! warning "The TaskId is `SlackStartup`"
    electron-builder hardcodes `TaskId="SlackStartup"` in the generated manifest (legacy from its Slack origins). This is harmless — Windows just treats it as an opaque identifier — but it means setting `startupTaskId = "MyCustom"` **alone does not change the manifest**. If you need a different TaskId, you must also post-process the generated `AppxManifest.xml`.

!!! info "Task Manager display lag (MSIX only)"
    On MSIX, toggling auto-launch via `AutoLaunch.enable()` / `disable()` **does not update the Task Manager "Startup apps" tab live**. Windows caches the display at session start and only refreshes it after the user logs off and back on (or reboots). The state is persisted correctly — `AutoLaunch.state()` returns the new value immediately, and the next login will honor the new setting. This is an OS-level Task Manager limitation, not an issue with the API. The Win32 backend (MSI / NSIS) does not have this lag.

## Win32 / MSI / NSIS behavior

No manifest is involved; the backend reads and writes two registry keys under `HKCU`:

- `Software\Microsoft\Windows\CurrentVersion\Run` — the actual launch entry (written by `enable()`).
- `Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run` — the user's Task Manager / Settings toggle state (read-only from our side unless the user explicitly disables via our own UI).

The value name defaults to `NucleusApp.appName` (or `appId` if missing). The `Run` value is written as `"<exe>" --nucleus-autostart`. You can inspect the `--nucleus-autostart` argument in your own `main()` to differentiate an auto-launched start from a manual one.

### `StartupApproved\Run` format (read-only)

Each entry is a 12-byte `REG_BINARY`:

```
Offset:  0  1  2  3   4  5  6  7  8  9  A  B
         └── DWORD ─┘ └────── FILETIME (QWORD LE) ──────┘
          flag (LE)   last user toggle timestamp
```

Nucleus uses the **parity rule** for robustness: `flag & 1 == 0` → enabled, `flag & 1 == 1` → disabled by user. In practice the values you'll see are `0x02` (enabled), `0x03` (disabled by user), and occasionally `0x06` (enabled, variant written by some installers).

## macOS behavior

Nucleus registers the main application with `SMAppService.mainApp` — the modern ServiceManagement API that Apple recommends since Ventura, and the one used by `sindresorhus/LaunchAtLogin-Modern`. The entry appears under **System Settings → General → Login Items → Open at Login** for both DMG (Developer ID) and PKG (Mac App Store, sandboxed) distributions. No helper app, no bundled plist, no build-time plugin configuration.

### Dependencies

The macOS path relies on a companion module for the JNI bridge to `SMAppService`:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.autolaunch:<version>")
    implementation("io.github.kdroidfilter:nucleus.service-management-macos:<version>")
}
```

`autolaunch` loads the macOS backend reflectively — if `service-management-macos` is absent from the classpath, `AutoLaunch.state()` returns `UNSUPPORTED` and the Windows / Linux paths remain unaffected.

### Status mapping

| `SMAppServiceStatus` | `AutoLaunchState` | Notes |
|---|---|---|
| `enabled` | `ENABLED` | User approved; launches at next login |
| `notRegistered` / `notFound` | `DISABLED` | No record in BackgroundTaskManagement yet — call `enable()` |
| `requiresApproval` | `DISABLED_BY_USER` | User must approve in System Settings |

### First-run approval

After `enable()`, macOS may leave the service in `requiresApproval` until the user opens **System Settings → Login Items** and flips the switch. Call `AutoLaunch.openSystemSettings()` to deep-link there.

### Detection of an auto-launched start

`AutoLaunch.wasStartedAtLogin(args)` on macOS combines two independent signals:

1. The `keyAELaunchedAsLogInItem` marker carried by the `kAEOpenApplication` AppleEvent that `loginwindow` dispatches at login. The native observer is installed at dylib-load time (`__attribute__((constructor))`), so it is in place before AWT's `NSApplication` starts its run loop and consumes the event.
2. The `LaunchInstanceID` environment variable that `launchd` injects into every process it spawns via `SMAppService`. Present at login-time start, absent on manual launches.

Either signal returning `true` is enough. The CLI `args` parameter is unused on macOS and kept for API symmetry with Windows.

### macOS < 13

`SMAppService` requires macOS 13.0+ (Ventura). On older releases, `AppServiceManager.isAvailable` returns `false` and every call reports `UNSUPPORTED` — there is no legacy fallback in the runtime.

## Linux behavior

Two backends, chosen at first access based on `ExecutableRuntime.isFlatpak()`. Both ride on a JNI bridge to GLib's GIO for D-Bus — no external library is linked at compile time.

### Host (deb / rpm / AppImage / dev runs) — systemd user service

`enable()` writes a transient unit at `~/.config/systemd/user/<app>.service`, calls `Reload` on `org.freedesktop.systemd1.Manager`, then `EnableUnitFiles` + `StartUnit`. `disable()` stops and disables the unit, then removes the file. `state()` is read from `ActiveState` / `UnitFileState` so the result reflects what systemd will actually do at next login — not just what is on disk.

The generated unit uses `Type=simple` with `WantedBy=default.target` and the process's own `ProcessHandle.current().info().command()` as `ExecStart` (override via `AutoLaunchConfig.executablePath`). The `--nucleus-autostart` marker is appended to `ExecStart` so `wasStartedAtLogin` works symmetrically with the other backends.

Login detection uses `INVOCATION_ID` — systemd injects it into every unit it spawns and it is not inherited across re-exec, so it is a reliable "started by systemd" signal even if the CLI marker is stripped.

### Flatpak — `org.freedesktop.portal.Background`

Inside a Flatpak sandbox, the user's systemd is unreachable — the portal is the only supported path. `enable()` calls `org.freedesktop.portal.Background.RequestBackground` with `autostart=true`, a `commandline` of `["flatpak", "run", "<app-id>", "--nucleus-autostart"]`, and the user-facing reason from `AutoLaunchConfig.backgroundReason` (default: `"Launch <appName> at login"`).

`state()` inspects the `~/.var/app/<id>/.../autostart/<id>.desktop` file exposed to the sandbox. `disable()` calls the same portal method with `autostart=false`. Both operations are asynchronous at the portal level; the bridge waits for the `Response` signal so calls are effectively synchronous from Kotlin.

### Dependencies

No extra module is required — the Linux native library ships inside `nucleus.autolaunch`. The backend uses `dlopen` on `libgio-2.0.so.0` at runtime, so the JAR runs unchanged on any modern Linux distribution (GLib 2.56+).

### `openSystemSettings()` on Linux

Returns `false`. There is no cross-desktop "startup apps" URL (GNOME, KDE, XFCE each manage autostart differently). Consumers that want to surface a shortcut can open `gnome-session-properties` or the distribution's equivalent themselves.

## Configuration overrides

All defaults fall back to `NucleusApp`. Override any of them **before** the first `AutoLaunch` call:

```kotlin
import io.github.kdroidfilter.nucleus.autolaunch.AutoLaunchConfig

AutoLaunchConfig.taskId            = "MyCustomMsixTaskId"              // MSIX
AutoLaunchConfig.executablePath    = "C:\\Program Files\\MyApp\\MyApp.exe" // Win32
AutoLaunchConfig.autostartArgument = "--autostart"                      // Win32, pass null to omit
AutoLaunchConfig.registryValueName = "MyApp"                            // Win32 HKCU\...\Run key
```

## Detecting an auto-launched start

```kotlin
fun main(args: Array<String>) {
    if (AutoLaunch.wasStartedAtLogin(args)) {
        // Started automatically — e.g. skip splash, start minimized in tray, etc.
    }
}
```

Detection is transparent across packaging types and uses the **signal that is deterministic for each backend**:

| Backend | Mechanism |
|---|---|
| Win32 (MSI / NSIS) | Looks for the marker argument (`--nucleus-autostart` by default, configurable via `AutoLaunchConfig.autostartArgument`) written into the `HKCU\...\Run` command line |
| macOS `SMAppService.mainApp` | Two signals — (1) the `kAEOpenApplication` AppleEvent carrying `keyAELaunchedAsLogInItem`, intercepted at dylib-load time before AWT consumes it; (2) the `LaunchInstanceID` env var that `launchd` injects into SMAppService-spawned processes. Either one fires `true` |
| MSIX packaged desktop | Walks up the process tree (skipping self-spawned jpackage launcher chains) and checks if the external ancestor is `sihost.exe` — the Shell Infrastructure Host that launches MSIX startup-task activations. Manual launches (Start menu, Explorer, taskbar) come from `explorer.exe` or `runtimebroker.exe` |
| Linux systemd user unit | Reads `INVOCATION_ID` — systemd injects it into every unit it spawns. Present at login-time start, absent on manual launches from a terminal or `.desktop` entry |
| Linux Flatpak portal | Looks for the marker argument injected into the portal's `commandline` at `enable()` time. `flatpak run <app-id>` is a single token so the portal's historical `Exec=` quoting bug does not apply |

Both paths are fully deterministic — no heuristics, no timing guesses, no runtime dependencies beyond the shipped native DLL.

## Rules to respect

Three invariants are enforced by the API — trying to work around them is counter-productive:

1. **Never loop on `BLOCKED_BY_USER`.** Both on MSIX (`DisabledByUser`) and Win32 (`StartupApproved` flag `0x03`), an explicit user disable is final until the user manually re-enables it via system UI. Calling `enable()` again is a no-op on MSIX and would disrespect an explicit choice on Win32. The backend detects this and returns `BLOCKED_BY_USER` without touching anything.

2. **Silent updates must not reset auto-launch state.** The Win32 backend only writes to `Run` when `enable()` is called. It never touches `StartupApproved` unless you call `disable()` (which removes both entries to avoid ghost items in Task Manager). This means an updater that reinstalls your app will not accidentally override a user's "off" choice.

3. **MSIX requires the manifest extension.** `AutoLaunch` on MSIX will return `UNSUPPORTED` / `ERROR` if `<uap5:StartupTask>` is missing from `Package.appxmanifest`. Use `addAutoLaunchExtension = true` (see above) and let the plugin handle it.

## GraalVM

The module ships `reachability-metadata.json` declaring `NativeAutoLaunchBridge` as JNI-accessible. No additional configuration is required for native-image builds.
