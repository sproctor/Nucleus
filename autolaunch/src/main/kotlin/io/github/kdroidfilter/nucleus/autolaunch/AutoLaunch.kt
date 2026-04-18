package io.github.kdroidfilter.nucleus.autolaunch

import io.github.kdroidfilter.nucleus.autolaunch.windows.NativeAutoLaunchBridge
import io.github.kdroidfilter.nucleus.autolaunch.windows.WindowsAutoLaunch
import io.github.kdroidfilter.nucleus.core.runtime.ExecutableRuntime

private const val MAC_SMAPPSERVICE_BACKEND_CLASS =
    "io.github.kdroidfilter.nucleus.autolaunch.macos.MacSMAppServiceBackend"

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
 * macOS builds (DMG and PKG alike) register themselves via
 * `SMAppService.mainApp` — the entry appears under **System Settings → Login
 * Items → Open at Login**. Requires macOS 13+; older releases return
 * [AutoLaunchState.UNSUPPORTED].
 *
 * Linux is no-op in this version.
 */
@Suppress("TooManyFunctions")
public object AutoLaunch {
    private val backend: AutoLaunchBackend by lazy { resolveBackend() }

    /**
     * Eagerly resolves the platform backend and loads any JNI library it requires.
     *
     * The first call into any `AutoLaunch` method triggers `System.load()` on the
     * calling thread. On macOS that first dlopen can take 100–300 ms due to AMFI
     * code-signature validation. Call [preload] from a background daemon thread
     * in `main()` to avoid blocking the UI thread later.
     */
    @JvmStatic
    public fun preload() {
        backend
    }

    /** Current auto-launch state. */
    @JvmStatic
    public fun state(): AutoLaunchState = backend.state()

    /**
     * Diagnostic string summarizing the resolved backend, the detected executable
     * type, and on Windows the underlying native JNI operations log. Safe to show
     * in debug UI; intended for troubleshooting a stuck `UNSUPPORTED` state.
     */
    @JvmStatic
    public fun diagnostic(): String =
        buildString {
            appendLine("backend: ${backend.javaClass.simpleName}")
            appendLine("os.name: ${System.getProperty("os.name")}")
            appendLine("executableType: ${ExecutableRuntime.type()}")
            macBackendResolutionError?.let { appendLine("macBackendError: $it") }
            append(backend.diagnosticSummary())
            append(NativeAutoLaunchBridge.getDiagnostic())
        }

    @Volatile
    private var macBackendResolutionError: String? = null

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
     * On Windows, opens `ms-settings:startupapps`. On macOS, opens
     * System Settings → General → Login Items.
     *
     * @return `true` if the system UI was launched successfully.
     */
    @JvmStatic
    public fun openSystemSettings(): Boolean = backend.openSystemSettings()

    /**
     * Detects whether this process was started by the auto-launch mechanism.
     *
     * Detection strategy is delegated to the resolved backend:
     * - **Win32 / MSI / NSIS** and **macOS user-dir LaunchAgent**: looks for the
     *   marker argument ([AutoLaunchConfig.autostartArgument], default
     *   `--nucleus-autostart`) written into the launch entry.
     * - **MSIX packaged desktop**: walks the process tree and checks whether an
     *   external ancestor is `sihost.exe` (Shell Infrastructure Host — how MSIX
     *   startup-task activations are issued). Supported since Windows 10 v1809.
     *
     * **Call this early** in `main()` — the result is cached after the first call.
     *
     * @param args the application's `main(args: Array<String>)` arguments
     */
    @JvmStatic
    public fun wasStartedAtLogin(args: Array<String>): Boolean {
        startupCheck?.let { return it }
        val result = backend.wasStartedAtLogin(args)
        startupCheck = result
        return result
    }

    private var startupCheck: Boolean? = null

    private fun resolveBackend(): AutoLaunchBackend {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> WindowsAutoLaunch
            os.contains("mac") -> loadMacSMAppServiceBackendOrFallback()
            else -> NoOpAutoLaunchBackend
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadMacSMAppServiceBackendOrFallback(): AutoLaunchBackend =
        try {
            val klass = Class.forName(MAC_SMAPPSERVICE_BACKEND_CLASS)
            klass.getField("INSTANCE").get(null) as AutoLaunchBackend
        } catch (t: Throwable) {
            // service-management-macos not on classpath — auto-launch on macOS
            // requires that dependency. The backend itself returns UNSUPPORTED
            // on macOS < 13 via AppServiceManager.isAvailable.
            macBackendResolutionError = "${t.javaClass.simpleName}: ${t.message}"
            NoOpAutoLaunchBackend
        }
}
