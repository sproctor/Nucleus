package io.github.kdroidfilter.nucleus.servicemanagement.macos

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_service_management"

@Suppress("TooManyFunctions")
internal object NativeMacServiceManagementBridge {
    private val callbackCounter = AtomicLong(0)
    private val callbacks = ConcurrentHashMap<Long, Any>()

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacServiceManagementBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // -- Callback management --------------------------------------------------

    fun <T : Any> registerCallback(callback: T): Long {
        val id = callbackCounter.incrementAndGet()
        callbacks[id] = callback
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> consumeCallback(id: Long): T? = callbacks.remove(id) as? T

    // -- Native method declarations -------------------------------------------

    /** Returns `true` if SMAppService is available (macOS 13.0+). */
    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    /**
     * Registers a service with the system.
     *
     * @param type 0 = loginItem, 1 = agent, 2 = daemon
     * @param identifier bundle identifier (loginItem) or plist filename (agent/daemon)
     * @return error message string, or `null` on success
     */
    @JvmStatic
    external fun nativeRegister(
        type: Int,
        identifier: String,
    ): String?

    /**
     * Unregisters a service. Calls back via [onUnregisterResult] when done.
     *
     * @param type 0 = loginItem, 1 = agent, 2 = daemon
     * @param identifier bundle identifier (loginItem) or plist filename (agent/daemon)
     * @param callbackId callback ID for result delivery
     */
    @JvmStatic
    external fun nativeUnregister(
        type: Int,
        identifier: String,
        callbackId: Long,
    )

    /**
     * Returns the raw SMAppServiceStatus value.
     *
     * @param type 0 = loginItem, 1 = agent, 2 = daemon
     * @param identifier bundle identifier (loginItem) or plist filename (agent/daemon)
     * @return raw status value (0..3)
     */
    @JvmStatic
    external fun nativeGetStatus(
        type: Int,
        identifier: String,
    ): Int

    /**
     * Opens the Login Items pane in System Settings.
     *
     * @return `true` if opened successfully
     */
    @JvmStatic
    external fun nativeOpenSystemSettingsLoginItems(): Boolean

    /**
     * Returns `true` if this process was launched at user login by `loginwindow`
     * (typically triggered by a registered [io.github.kdroidfilter.nucleus.servicemanagement.AppService.MainApp]
     * login item). Detection is based on the `kAEOpenApplication` AppleEvent whose
     * `keyAEPropData` parameter equals `keyAELaunchedAsLogInItem` — the same signal
     * Cocoa apps read from `NSAppleEventManager.currentAppleEvent`.
     *
     * The dylib installs its observer during `__attribute__((constructor))`, so the
     * flag is captured before AWT's `NSApplication` replaces the handler. Callers
     * can invoke this safely at any point after the native library is loaded.
     */
    @JvmStatic
    external fun nativeWasLaunchedAsLoginItem(): Boolean

    // -- Callbacks from native code -------------------------------------------

    /** Called by native code when [nativeUnregister] completes. */
    @JvmStatic
    fun onUnregisterResult(
        callbackId: Long,
        error: String?,
    ) {
        val callback = consumeCallback<(String?) -> Unit>(callbackId) ?: return
        callback(error)
    }
}
