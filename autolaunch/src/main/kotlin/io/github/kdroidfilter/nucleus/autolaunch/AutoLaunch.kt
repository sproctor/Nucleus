package io.github.kdroidfilter.nucleus.autolaunch

import io.github.kdroidfilter.nucleus.autolaunch.windows.NativeAutoLaunchBridge
import io.github.kdroidfilter.nucleus.autolaunch.windows.WindowsAutoLaunch

/**
 * Cross-platform auto-launch at user login.
 *
 * Usage:
 * ```
 * when (val state = AutoLaunch.state()) {
 *     AutoLaunchState.ENABLED -> {}
 *     AutoLaunchState.DISABLED -> AutoLaunch.enable()
 *     AutoLaunchState.DISABLED_BY_USER -> AutoLaunch.openSystemSettings()
 *     else -> {}
 * }
 * ```
 *
 * Windows implementation auto-detects MSIX vs Win32 at runtime and uses
 * the appropriate API (`Windows.ApplicationModel.StartupTask` for MSIX,
 * `HKCU\...\Run` + `StartupApproved\Run` for MSI/NSIS).
 *
 * macOS and Linux backends are no-op in this version; APIs return
 * [AutoLaunchState.UNSUPPORTED] / [AutoLaunchResult.UNSUPPORTED].
 */
public object AutoLaunch {
    private val backend: AutoLaunchBackend by lazy { resolveBackend() }

    /** Current auto-launch state. */
    @JvmStatic
    public fun state(): AutoLaunchState = backend.state()

    /** Diagnostic log of native auto-launch operations (Windows only, empty on other platforms). */
    @JvmStatic
    public fun diagnostic(): String = NativeAutoLaunchBridge.getDiagnostic()

    /** `true` if the app is configured to launch at login. */
    @JvmStatic
    public fun isEnabled(): Boolean =
        when (state()) {
            AutoLaunchState.ENABLED, AutoLaunchState.ENABLED_BY_POLICY -> true
            else -> false
        }

    /**
     * `true` if auto-launch was explicitly disabled by the user via system UI.
     * In that state, programmatic re-enable is a no-op — show the user a
     * link to [openSystemSettings] instead.
     */
    @JvmStatic
    public fun isUserLocked(): Boolean = state() == AutoLaunchState.DISABLED_BY_USER

    /**
     * Request auto-launch to be enabled.
     *
     * Returns [AutoLaunchResult.BLOCKED_BY_USER] if the user has explicitly
     * disabled via system UI — do NOT loop on this, it will never succeed
     * without manual user action.
     */
    @JvmStatic
    public fun enable(): AutoLaunchResult = backend.enable()

    /** Request auto-launch to be disabled. */
    @JvmStatic
    public fun disable(): AutoLaunchResult = backend.disable()

    /**
     * Opens the platform system settings page for managing startup apps.
     * On Windows, opens `ms-settings:startupapps`.
     *
     * @return `true` if the system UI was launched successfully.
     */
    @JvmStatic
    public fun openSystemSettings(): Boolean = backend.openSystemSettings()

    /**
     * Detects whether this process was started by the auto-launch mechanism.
     *
     * Detection strategy:
     * - **Win32 / MSI / NSIS**: looks for the marker argument written by Nucleus
     *   into the `HKCU\...\Run` entry (default `--nucleus-autostart`, configurable
     *   via [AutoLaunchConfig.autostartArgument]).
     * - **MSIX packaged desktop**: calls
     *   `Windows.ApplicationModel.AppInstance.GetActivatedEventArgs()` and checks
     *   for `ActivationKind.StartupTask`. Supported since Windows 10 v1809.
     *
     * **Call this early** in `main()` — on MSIX the activation context is a
     * one-shot query; the result is cached after the first call.
     *
     * @param args the application's `main(args: Array<String>)` arguments
     */
    @JvmStatic
    public fun wasStartedAtLogin(args: Array<String>): Boolean = startedAtLogin(args)

    private var startupCheck: Boolean? = null

    private fun startedAtLogin(args: Array<String>): Boolean {
        startupCheck?.let { return it }
        val result = checkStartedAtLogin(args)
        startupCheck = result
        return result
    }

    private fun checkStartedAtLogin(args: Array<String>): Boolean {
        val packaged = NativeAutoLaunchBridge.isLoaded && NativeAutoLaunchBridge.isPackaged()
        return if (packaged) {
            // MSIX: windows.startupTask is implemented via Task Scheduler,
            // so the parent process is taskhostw.exe for auto-launches.
            NativeAutoLaunchBridge.isLaunchedByTaskScheduler() == 1
        } else {
            // Win32 / MSI / NSIS / portable / dev → CLI marker injected by enable().
            val marker = AutoLaunchConfig.autostartArgument?.takeIf { it.isNotBlank() }
            marker != null && args.any { it == marker }
        }
    }

    private fun resolveBackend(): AutoLaunchBackend {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> WindowsAutoLaunch
            else -> NoOpAutoLaunchBackend
        }
    }
}
