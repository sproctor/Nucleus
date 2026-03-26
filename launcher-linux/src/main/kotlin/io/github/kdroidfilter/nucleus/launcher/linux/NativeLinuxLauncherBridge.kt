package io.github.kdroidfilter.nucleus.launcher.linux

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_launcher_linux"

internal object NativeLinuxLauncherBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxLauncherBridge::class.java)
    val isLoaded: Boolean get() = loaded

    // ---- Native methods ------------------------------------------------

    /**
     * Emits a `com.canonical.Unity.LauncherEntry.Update` signal on the session bus.
     *
     * Boolean flags use tri-state: -1 = not set, 0 = false, 1 = true.
     * Nullable strings pass null when the property should be omitted.
     *
     * @return `true` if the signal was emitted successfully.
     */
    @JvmStatic
    external fun nativeUpdate(
        appUri: String,
        hasCount: Boolean,
        count: Long,
        countVisible: Int,
        hasProgress: Boolean,
        progress: Double,
        progressVisible: Int,
        urgent: Int,
        quicklist: String?,
        updating: Int,
    ): Boolean

    /**
     * Handles an incoming `com.canonical.Unity.LauncherEntry.Query` method call.
     *
     * Registers a D-Bus object that responds to Query with the current state.
     * Must be called once to enable Query support.
     *
     * @return `true` if the object was registered successfully.
     */
    @JvmStatic
    external fun nativeRegisterQueryHandler(appUri: String): Boolean

    /**
     * Updates the internal state returned by Query without emitting an Update signal.
     */
    @JvmStatic
    external fun nativeSetState(
        hasCount: Boolean,
        count: Long,
        countVisible: Int,
        hasProgress: Boolean,
        progress: Double,
        progressVisible: Int,
        urgent: Int,
        quicklist: String?,
        updating: Int,
    )

    /**
     * Unregisters the Query handler and releases D-Bus resources.
     */
    @JvmStatic
    external fun nativeUnregister()
}
